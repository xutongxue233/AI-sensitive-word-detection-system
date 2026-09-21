# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 本仓库代码注释、文档以简体中文为主;沿用此风格。

## 项目概述

视频内容审核工作台:上传视频/字幕 → 抽音频 Whisper ASR + 画面硬字幕 OCR 双层转写 → 规则召回敏感词候选 → AI 上下文复核 → 生成可确认/可调整/可导出的剪辑(音频)与去字幕(画面)建议。核心目标是**可审计、可解释、可定位到时间轴**,而非黑盒整段多模态审核。

## 架构:四进程拓扑(关键)

系统是混合多进程架构,四个运行时各自独立、通过 HTTP/REST 协作。理解为何拆分是看懂本仓库的前提:

| 进程 | 技术 | 端口 | 角色 |
| --- | --- | --- | --- |
| backend | Spring Boot 3.3 / Java 21 / MyBatis-Plus / SQLite(默认) / MySQL(可选) | 8090 | 编排管线、REST API、规则匹配、AI 客户端、FFmpeg 调用、导出 |
| frontend | React 18 / Vite / TS | 5174 | 审核工作台,dev 代理 `/api` → 8090 |
| asr-service | FastAPI / faster-whisper(**CTranslate2,纯 CPU**) | 9000 | 语音转写,词级时间戳 |
| ocr-service | FastAPI / RapidOCR(**onnxruntime,可选 DirectML 核显**) | 9001 | 画面硬字幕识别 |

**两个 Python 服务均为轻量引擎**(面向核显/无 NVIDIA 机器):ASR 用 faster-whisper(CTranslate2 INT8,纯 CPU——CTranslate2 无任何核显后端,勿尝试给 ASR 上 GPU),OCR 用 rapidocr-onnxruntime(PP-OCRv4 模型随 wheel 自带、离线可用,无 paddle)。OCR 默认安装 CPU onnxruntime，只有设置 `OCR_USE_DML=1` 才换装 `onnxruntime-directml` 走核显推理；抽帧解码默认尝试 D3D11 硬解、失败回退软解(`OCR_HW_DECODE`)。两服务仍保持独立进程、独立 venv 的拓扑(职责与依赖隔离);历史上的 torch/paddle CUDA 冲突随引擎更换已不存在,但**不要**往这两个 venv 里引入 torch/paddle 等重依赖。OCR 模块内 `PADDLE_OCR_*` 环境变量名与 `probe_paddle_gpu` 等公开名是 PaddleOCR 时代的遗留命名,为兼容 /health 字段与既有配置而保留。

后端是混合架构中唯一不可被 Python 替代的部分(转写/OCR 推理保持独立进程,职责与依赖隔离)。`backend/tools/ffmpeg/bin/{ffmpeg,ffprobe}.exe` 不入库,由本地提供或环境变量 `FFMPEG_PATH`/`FFPROBE_PATH` 覆盖。

## 检测管线(DetectionPipelineService.processAsync)

`@Async` 异步执行,`VideoService.createDetectionJob` 触发。各阶段写 `JobStatus` + 进度百分比,前端轮询 `GET /jobs/{id}` 展示:

1. **EXTRACTING_AUDIO(10)** → `buildTranscript`:**音画两腿并行**(见下)
2. **TRANSCRIBING(35)** → `transcriptService.replaceTranscript` 落库 segments/words
3. **MATCHING_TERMS(55)** → `ruleMatchingService.matchJob` 规则召回候选
4. **AI_REVIEWING(75)** → `aiExtractionService.extractAndReview`
5. **SUGGESTING_CLIPS(90)** → `clipSuggestionService.createSuggestions`
6. **COMPLETED(100)**,`finally` 始终清理 `job-{id}/audio.wav` 临时目录

### 音画两腿并行(buildTranscript)

用**专用线程池 `transcriptExecutor`**(故意不注册为 Spring Bean)跑两腿,避免与 `@Async` 框架执行器同池导致 join 子任务自饥饿死锁——改动并发逻辑时务必保持此隔离。

- **腿 A 音频**:FFmpeg 抽 16k mono wav → Whisper 转写。**失败 = 任务失败,不降级**。转写引擎可在运行时设置切换:LOCAL(本地 faster-whisper)或 ONLINE(OpenAI Chat Completions 兼容在线 ASR,如小米 MiMo `mimo-v2.5-asr`)。在线模式仍经由 asr-service 进程:后端把端点/密钥/模型以 form 字段透传,asr-service 用 faster-whisper 自带 Silero VAD 按静音切片逐片上送(在线 API 只返回纯文本),片段起止作段级时间戳、段内按字符权重线性插值出**伪词级时间戳**——响应结构与本地模式一致,下游(规则召回完全建立在词级时间戳上,words 为空的段不参与匹配)无需改动,且不加载本地 Whisper 模型。
- **腿 B 画面 OCR**:仅当无外部字幕文件时跑;**失败降级为空,不拖垮音频腿**。
- 有外部 `.srt/.vtt` 时跳过腿 B,改用 `SubtitleParser`。
- 各层经 `TranscriptionMerger.merge` 按 source + 时间重叠去重合并。同一时段音频与字幕文本相同也各保留一条(`TranscriptSource` 不同,导出处理方式不同)。

### 两种 AI 审核策略(关键设计)

AI 启用与否、端点/密钥/模型走的是**运行时设置**(见下),`extractAndReview` 据此分流:

- **整篇提取(主,AI 启用时)**:`AiModerationClient.extract` 把整篇字幕(每 `BATCH_SIZE=40` 段一批,批次在专用线程池上 4 并发并行调用,进度按完成批次数回报管线映射到 75~89)+ **全量词库**一次交给模型提取所有命中,用词级时间戳定位。与规则候选合并去重,未被覆盖的规则残余仍走复核兜底。
- **候选复核(回退)**:`AiReviewService.reviewHits` 按 10 条一批走 `AiModerationClient.reviewBatch` 批量判定(decisions 按 index 对齐;批量失败或模型遗漏的条目回退单条 `review`)。用于:AI 未启用(本地兜底,规则命中保留为 0.70 置信度)、整篇提取整体失败、或残余规则候选。
- **置信度把关**:仅 `violation=true && confidence >= 阈值(默认 0.6)` 才置 `ReviewStatus.VIOLATION`、进时间轴并生成剪辑;否则置 `SAFE`,保留记录与原因但不自动剪。

## 其他关键设计

- **运行时设置(SettingsService + `app_settings` 单行表)**:AI 与剪辑参数、ASR 引擎(本地/在线及其端点、密钥、模型)可在前端齿轮即时改,对**新建任务**立即生效、无需重启。`application.yml` 的 `app.ai.*`/`app.clip.*` 仅在 DB 无记录时**种子化**。内存缓存,`GET /settings` 不回传明文 API Key(只给 `aiApiKeyConfigured`/`asrOnlineApiKeyConfigured`)。
- **OpenAI 兼容客户端(AiModerationClient)**:支持 CHAT(`/v1/chat/completions`)与 RESPONSES(`/v1/responses`)两形态;结构化输出**自动降级阶梯** `json_schema → json_object → none`(按 400 报文判别并按端点缓存可用档位);对夹带解释文字/代码块的国产网关做容错 JSON 解析。因设置可变,每次调用临时构建 `RestClient`。
- **两种命中两种导出(`TranscriptSource`)**:`AUDIO` 命中 → `exportWithoutClips` 删时间片段(保留段 concat);`VIDEO_SUBTITLE` 命中 → `exportWithSubtitleBlur` 用 FFmpeg **delogo 邻域插值去字幕** + 高斯柔化 + 边缘羽化(探测分辨率失败回退盒式模糊)。擦除时段用整条字幕显示时长(非命中词的零点几秒)以免闪烁。字幕框 bbox(归一化 0~1)由 OCR 写入 `transcript_segments`。**GPU 去字幕(VSR)已移除**,统一用 CPU 的 ffmpeg delogo,`subtitle-blur-sigma`/`subtitle-feather-max` 在 `application.yml` 调。导出**重编码**支持硬件编码器(`app.ffmpeg.hw-encoder`,默认 auto 按 AMF→QSV→NVENC 试编码懒探测,核显即可;真实导出失败自动降级 libx264 重跑,硬件路径永不阻断导出),滤镜仍在 CPU 执行。
- **规则匹配(RuleMatchingService)**:`MatchType` 四类 EXACT/VARIANT/REGEX/SEMANTIC。SEMANTIC 目前仅价格语义(中英文带单位、符号小数、口语约数如「60几」「几十块」),靠正则在价格上下文召回候选交 AI 判真伪。

## 数据与迁移

- 默认数据库是 SQLite,启动时 `spring.sql.init` 执行 `schema-sqlite.sql` 建表(`CREATE TABLE IF NOT EXISTS`),数据库文件在 `backend/video_moderation.db`;MySQL 通过 `mysql` profile 启用并使用 `schema-mysql.sql`。
- **新增列走 `SchemaMigration`**(`ApplicationRunner`,MySQL 用 `information_schema`,SQLite 用 `PRAGMA table_info` 幂等加列),**不要**只改 schema 脚本期望它对已有库生效。
- 核心表:`violation_terms`、`videos`、`detection_jobs`、`transcript_segments`(+bbox/source)、`transcript_words`(词级时间戳)、`term_hits`(+source/review_status/ai_confidence)、`ai_reviews`、`clip_suggestions`、`app_settings`。

## 代码组织约定(backend `com.ai.moderation`)

`controller`(REST,统一 `/api/v1`) · `service`(业务,小工具/不可变记录放 `service/support`) · `repository`(MyBatis-Plus mapper,继承 `common.mybatis.BaseCrudMapper`) · `domain`(实体 + 枚举) · `dto`(请求/响应,多为 record) · `config`(`@ConfigurationProperties` record + CORS/迁移) · `asr`(转写管线:Whisper/OCR 客户端、SubtitleParser、Merger、TextNormalizer) · `common`(异常/全局处理)。前端 `App.tsx` 单页编排 + `api.ts`(axios 类型化客户端)+ `types.ts`;UI 同时用 **Ant Design** 与 **shadcn/ui 风格**(Radix + Tailwind + CVA,`components/ui/`)两套。

## 常用命令

一键脚本(仓库根目录):`dev.bat` 开发模式启动全部四服务(源码直跑,日志带 `[backend]/[frontend]/[asr]/[ocr]` 前缀聚合到同一窗口,Ctrl+C 全停);`stop.bat` 按端口停止全部服务进程树(对 dev/生产/手动/遗留孤儿进程一律有效);`start-local.bat` 生产模式(前端打进 jar 后台跑,支持 `stop|status|setup|rebuild` 子命令)。

**后端必须用 JDK 21**(默认 `JAVA_HOME` 是 JDK8,直接 `mvn` 会对 record/text block 报假语法错):

```bash
export JAVA_HOME=/d/Environment/jdk-21.0.2   # bash on Windows;命令行构建前必做
cd backend
mvn spring-boot:run                          # 起后端 :8090
mvn test                                      # 全部单测
mvn test -Dtest=RuleMatchingServiceTest       # 单个测试类(#method 跑单方法)
mvn -DskipTests package                       # 打包
```

```bash
# 前端 :5174,dev 代理 /api → :8090
cd frontend && npm install && npm run dev
npm run build      # tsc -b && vite build
```

```bash
# ASR 服务 :9000(首次跑会经 HF_ENDPOINT 镜像下载 faster-whisper 模型,缓存在 WHISPER_DOWNLOAD_ROOT)
cd asr-service && .\.venv\Scripts\python.exe -m uvicorn app:app --host 127.0.0.1 --port 9000
# OCR 服务 :9001(独立 venv;RapidOCR 模型随包自带,免下载)
cd ocr-service && .\.venv\Scripts\python.exe -m uvicorn ocr_app:app --host 127.0.0.1 --port 9001
```

ASR 纯 CPU(faster-whisper INT8);OCR 装 onnxruntime-directml 时自动核显推理,否则纯 CPU。引擎信息看各自 `/health` 的 `gpu` 字段(仅排查用,后端不解析)。模型/设备等环境变量见 `config/local.env.example`。

## 重要陷阱

- **JDK 21**:命令行构建前必须 `export JAVA_HOME=/d/Environment/jdk-21.0.2`,否则假语法错。
- **Python 服务保持轻量**:asr/ocr 两 venv 不要引入 torch/paddle 等重依赖(已换 faster-whisper / rapidocr-onnxruntime;OCR 的核显加速走 onnxruntime-directml,不是 CUDA)。
- **README 里的 `start-all.bat`/`stop-all.bat`/`_run-*.bat` 已删除**,现用根目录 `dev.bat`(开发启动)/`stop.bat`(停止)/`start-local.bat`(生产)。
- Shell 是 Windows 上的 bash:用正斜杠、`/dev/null`。
- AI 复核只处理规则/提取召回的候选,不做全文无差别审核。
