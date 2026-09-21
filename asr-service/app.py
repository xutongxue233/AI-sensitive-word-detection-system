"""ASR 语音转写微服务(FastAPI + faster-whisper / CTranslate2,纯 CPU 轻量版)。

四进程架构中的 ``asr-service``,默认监听 ``127.0.0.1:9000``,由 Java 后端经
``app.asr.base-url`` 通过 HTTP 调用:接收后端用 FFmpeg 抽取的 16k mono 音频,
转写为带词级时间戳的 segments 返回(供后端做规则召回与时间轴定位)。

为适配只有核显(无 NVIDIA/CUDA)的机器,本服务用 faster-whisper(CTranslate2 引擎)
在纯 CPU 上以 INT8 量化推理:比 openai-whisper 约快 4 倍、内存更省,且不依赖 torch/CUDA。
对外 HTTP 接口(/、/health、/transcribe)与原 openai-whisper 版完全一致,后端无需改动。

除本地 faster-whisper 外,``/transcribe`` 还支持按请求切换到**在线 ASR**(OpenAI Chat
Completions 兼容形态,如小米 MiMo ``mimo-v2.5-asr``):后端把运行时设置里的端点/密钥/模型
以 form 字段透传过来。在线 API 只返回整段纯文本、无时间戳,因此本服务先用 faster-whisper
自带的 Silero VAD(onnxruntime,无新增依赖)把音频按静音切成语音片段,逐片上送识别,
片段起止即段级时间戳,段内再按字符权重线性插值生成伪词级时间戳——响应结构与本地模式
完全一致,后端管线(规则召回 / 时间轴定位 / 剪辑建议)无需任何改动。在线模式不加载
本地 Whisper 模型。
"""

import base64
import io
import logging
import os
import tempfile
import threading
import time
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import requests
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio
from faster_whisper.vad import VadOptions, get_speech_timestamps

try:
    from opencc import OpenCC
except Exception:  # opencc 缺失时退化为不做简繁转换,不致命
    OpenCC = None


# 模型规格:默认 medium(faster-whisper INT8 下 CPU 可接受且较准);可用 WHISPER_MODEL 覆盖。
# 求快可用 small/base,求准可用 large-v3。
MODEL_SIZE = os.getenv("WHISPER_MODEL", "medium")
# 本地 CT2 模型目录优先于 WHISPER_MODEL。将该目录复制到没有代理的电脑后，
# faster-whisper 不需要访问 HuggingFace；不存在时会快速返回 503，而不会悄悄联网下载。
MODEL_PATH = os.getenv("WHISPER_MODEL_PATH", "").strip()


def _env_bool(name: str, default: bool = False) -> bool:
    """解析常见环境变量布尔值，兼容 1/true/yes/on 与 0/false/no/off。"""
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"1", "true", "yes", "on", "y", "t"}


# WHISPER_LOCAL_FILES_ONLY 是本服务开关，HF_HUB_OFFLINE 是 HuggingFace 标准开关；
# 任一开启都传给 faster-whisper，避免无代理电脑在首次任务中长时间等待。
LOCAL_FILES_ONLY = _env_bool("WHISPER_LOCAL_FILES_ONLY") or _env_bool("HF_HUB_OFFLINE")
# 默认仍是懒加载；设置 WHISPER_PRELOAD=true 可在服务启动时预热模型。
PRELOAD_MODEL = _env_bool("WHISPER_PRELOAD")
# 设备:核显机器固定 cpu。保留 cuda 别名规范化,但 CTranslate2 GPU 需 CUDA,核显无效。
_device_lower = os.getenv("WHISPER_DEVICE", "cpu").strip().lower()
if _device_lower in {"gpu", "cuda"} or _device_lower.startswith("cuda:"):
    DEVICE = "cuda"
else:
    DEVICE = "cpu"
# CTranslate2 计算精度:CPU 默认 int8(最快最省内存);cuda 默认 float16。可用 WHISPER_COMPUTE_TYPE 覆盖。
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE") or ("float16" if DEVICE == "cuda" else "int8")
# CPU 线程数:0 = 让 CTranslate2 自动按物理核数。
CPU_THREADS = int(os.getenv("WHISPER_CPU_THREADS", "0"))
# 模型权重下载目录(从 HuggingFace 拉 CT2 权重),指向项目内以免污染本地用户目录。
DOWNLOAD_ROOT = os.getenv("WHISPER_DOWNLOAD_ROOT") or None
# OpenCC 简繁转换配置名:默认 t2s(繁转简),空串则不转换。
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
# 引导提示:让模型倾向输出简体中文普通话内容。
INITIAL_PROMPT = os.getenv("WHISPER_INITIAL_PROMPT", "请使用简体中文转写普通话内容。")
# beam search:默认 5,数字/口语识别更准;设 0 改用贪心解码(beam=1),更快。
WHISPER_BEAM_SIZE = int(os.getenv("WHISPER_BEAM_SIZE", "5"))
# 语言:默认 None(自动检测);可设 zh 固定为中文。
WHISPER_LANGUAGE = os.getenv("WHISPER_LANGUAGE") or None
# 仅为兼容原 /health 的 fp16 字段保留(CPU INT8 下无实际意义)。
FP16 = os.getenv("WHISPER_FP16", "false").lower() in {"1", "true", "yes", "on"}

# ---- 在线 ASR(MiMo 等 OpenAI Chat Completions 兼容)参数,均可用环境变量微调 ----
# VAD/上送统一按 16k 采样处理(与后端 FFmpeg 抽取的 wav 一致)。
ONLINE_SAMPLE_RATE = 16000
# 单个语音片段上限秒数:在线 API 按整片返回文本,片段越短伪词时间戳越准,但调用次数越多。
ONLINE_MAX_SPEECH_SECONDS = float(os.getenv("ASR_ONLINE_MAX_SPEECH_SECONDS", "28"))
# 静音超过该毫秒数即切片(Silero VAD)。
ONLINE_MIN_SILENCE_MS = int(os.getenv("ASR_ONLINE_MIN_SILENCE_MS", "500"))
# 片段首尾保留的语音垫片毫秒数,避免切掉吞音的字头字尾。
ONLINE_SPEECH_PAD_MS = int(os.getenv("ASR_ONLINE_SPEECH_PAD_MS", "150"))
# 并发上送的片段数(对在线 API 的并发请求数)。在线服务普遍有 QPS/并发限流,默认保守取 2;
# 触发 429 时会按退避重试,但并发越高越容易反复撞限。
ONLINE_CONCURRENCY = max(1, int(os.getenv("ASR_ONLINE_CONCURRENCY", "2")))
# 单片识别请求超时秒数。
ONLINE_TIMEOUT_SECONDS = int(os.getenv("ASR_ONLINE_TIMEOUT_SECONDS", "120"))
# 单片在 429 限流/5xx/网络抖动时的最大重试次数(指数退避,优先尊重 Retry-After 响应头)。
ONLINE_MAX_RETRIES = max(0, int(os.getenv("ASR_ONLINE_MAX_RETRIES", "5")))
# 退避基数秒:第 n 次重试等待 base * 2^n(上限 30s)。
ONLINE_RETRY_BASE_SECONDS = float(os.getenv("ASR_ONLINE_RETRY_BASE_SECONDS", "1.5"))
# 单次退避等待上限秒数。
ONLINE_RETRY_MAX_WAIT_SECONDS = float(os.getenv("ASR_ONLINE_RETRY_MAX_WAIT_SECONDS", "30"))

app = FastAPI(title="faster-whisper ASR Service")
model = None
converter = OpenCC(CHINESE_CONVERTER) if (CHINESE_CONVERTER and OpenCC is not None) else None
_model_load_error = None
_logger = logging.getLogger("asr-service")
# 初始化锁:保护全局模型懒加载(double-checked locking)。
_model_lock = threading.Lock()
# 推理锁:同一全局模型并发推理不一定安全(多任务并行时),故串行化。
_model_infer_lock = threading.Lock()


@app.get("/")
async def root():
    """根路径说明:避免浏览器/IDE 探活访问 `/` 时误报 404。"""
    return {
        "service": "asr-service",
        "status": "ok",
        "engine": "faster-whisper",
        "health": "/health",
        "transcribe": "/transcribe",
        "note": "OCR 服务是独立进程 ocr-service/ocr_app.py,默认端口 9001。",
    }


def _probe_engine() -> dict:
    """返回引擎/设备自检信息,供 /health 暴露(替代原 torch 探测;后端不解析此字段)。

    任何探测异常都不抛出,仅记入 engineError,保证健康检查不被环境问题拖垮。
    """
    info = {
        "engine": "faster-whisper",
        "computeType": COMPUTE_TYPE,
        "cpuThreads": CPU_THREADS or "auto",
    }
    try:
        import ctranslate2
        info["ctranslate2Version"] = ctranslate2.__version__
        try:
            info["cudaDeviceCount"] = ctranslate2.get_cuda_device_count()
        except Exception:
            info["cudaDeviceCount"] = 0
    except Exception as exc:
        info["engineError"] = f"{type(exc).__name__}: {exc}"
    return info


@app.get("/health")
async def health():
    """健康检查:返回模型规格、目标设备、计算精度、模型是否已加载,以及引擎自检信息。

    顶层字段(status/model/device/fp16/modelLoaded/gpu)与原版兼容;
    gpu 子字段改为 faster-whisper 引擎信息(后端不解析此字段,仅供排查)。
    """
    return {
        "status": "ok",
        "model": MODEL_SIZE,
        "modelPath": _resolved_model_path(),
        "modelAvailable": _model_available(),
        "modelLocalFilesOnly": LOCAL_FILES_ONLY,
        "modelPreload": PRELOAD_MODEL,
        "modelLoadError": _model_load_error,
        "device": DEVICE,
        "fp16": FP16,
        "computeType": COMPUTE_TYPE,
        "modelLoaded": model is not None,
        "gpu": _probe_engine(),
    }


def normalize_text(text: str) -> str:
    """去首尾空白并按 CHINESE_CONVERTER 做简繁转换(默认 t2s 繁转简)。

    未配置转换器时仅做 strip,不改字形。
    """
    value = (text or "").strip()
    return converter.convert(value) if converter else value


def _resolved_model_path() -> str | None:
    """返回配置的本地模型目录（若存在），供 health 和加载逻辑复用。"""
    if not MODEL_PATH:
        return None
    return str(Path(MODEL_PATH).expanduser().resolve(strict=False))


def _local_model_dir() -> Path | None:
    """解析本地模型目录，也接受 HuggingFace cache 根目录作为输入。"""
    configured = _resolved_model_path()
    if not configured:
        return None
    path = Path(configured)
    if not path.is_dir():
        return None
    if (path / "config.json").is_file():
        return path
    # faster-whisper 的 download_root 通常是
    # models--Systran--faster-whisper-*/snapshots/<revision>，允许用户直接
    # 将该缓存根目录填入 WHISPER_MODEL_PATH，省去手工寻找 revision。
    candidates = [candidate.parent for candidate in path.glob("models--*/snapshots/*/config.json")]
    if not candidates:
        candidates = [candidate.parent for candidate in path.glob("snapshots/*/config.json")]
    if candidates:
        return max(candidates, key=lambda candidate: candidate.stat().st_mtime)
    # WHISPER_MODEL_PATH 也允许指向尚未被 faster-whisper 校验的 CT2 目录；
    # 交给引擎报告缺少文件，比把用户配置误判成“未配置”更容易排查。
    if not any(path.glob("models--*")) and not (path / "snapshots").is_dir():
        return path
    return None


def _cached_model_dir() -> Path | None:
    """查找当前模型规格在 HuggingFace cache 中的已下载 snapshot。"""
    cache_roots = []
    if DOWNLOAD_ROOT:
        cache_roots.append(Path(DOWNLOAD_ROOT).expanduser())
    for env_name in ("HF_HUB_CACHE", "HUGGINGFACE_HUB_CACHE"):
        value = os.getenv(env_name, "").strip()
        if value:
            cache_roots.append(Path(value).expanduser())
    hf_home = os.getenv("HF_HOME", "").strip()
    cache_roots.append(Path(hf_home).expanduser() / "hub" if hf_home else Path.home() / ".cache" / "huggingface" / "hub")

    model_name = MODEL_SIZE.rsplit("/", 1)[-1]
    for cache_root in cache_roots:
        snapshot_root = cache_root / f"models--Systran--faster-whisper-{model_name}" / "snapshots"
        candidates = [candidate.parent for candidate in snapshot_root.glob("*/config.json")]
        if candidates:
            return max(candidates, key=lambda candidate: candidate.stat().st_mtime)
    return None


def _model_available() -> bool:
    """判断模型引用是否已在本地可用，不触发网络下载或模型加载。"""
    if model is not None:
        return True
    if MODEL_PATH:
        return _local_model_dir() is not None
    return _cached_model_dir() is not None


def get_model():
    """double-checked locking 懒加载全局 faster-whisper 模型,返回已加载实例。

    首次加载按需从 HuggingFace 下载 CT2 权重(可经 HF_ENDPOINT 走镜像、download_root 落项目内)。
    加载失败抛 503 快速失败,避免静默卡住。
    """
    global model, _model_load_error
    if model is None:
        with _model_lock:
            if model is None:
                model_ref = MODEL_SIZE
                local_path = _local_model_dir()
                if MODEL_PATH:
                    if local_path is None:
                        detail = f"WHISPER_MODEL_PATH 未找到可用模型目录: {_resolved_model_path()}"
                        _model_load_error = detail
                        raise HTTPException(status_code=503, detail=detail)
                    model_ref = str(local_path)

                kwargs = {
                    "device": DEVICE,
                    "compute_type": COMPUTE_TYPE,
                    "local_files_only": LOCAL_FILES_ONLY,
                }
                if DOWNLOAD_ROOT:
                    kwargs["download_root"] = DOWNLOAD_ROOT
                if CPU_THREADS > 0:
                    kwargs["cpu_threads"] = CPU_THREADS
                try:
                    model = WhisperModel(model_ref, **kwargs)
                    _model_load_error = None
                except Exception as exc:
                    _model_load_error = f"{type(exc).__name__}: {exc}"
                    raise HTTPException(
                        status_code=503,
                        detail=(
                            f"加载 faster-whisper 模型失败(model={model_ref}, device={DEVICE}, "
                            f"compute_type={COMPUTE_TYPE}): {type(exc).__name__}: {exc}"
                        ),
                    )
    return model


@app.on_event("startup")
async def preload_model_if_requested():
    """按需预热模型；失败只记录到 health，不阻止 HTTP 服务启动。"""
    if not PRELOAD_MODEL:
        return
    try:
        await run_in_threadpool(get_model)
        _logger.info("faster-whisper model preloaded: %s", _resolved_model_path() or MODEL_SIZE)
    except Exception as exc:
        _logger.warning("faster-whisper model preload failed: %s", exc)


def _online_endpoint(base_url: str) -> str:
    """把在线 ASR 基址拼成 chat/completions 端点,容忍末尾斜杠与是否已带 /v1。"""
    trimmed = (base_url or "").strip().rstrip("/")
    if not trimmed:
        raise HTTPException(status_code=400, detail="在线 ASR 未配置 Base URL")
    if trimmed.endswith("/v1"):
        return trimmed + "/chat/completions"
    return trimmed + "/v1/chat/completions"


def _encode_wav_base64(samples: np.ndarray) -> str:
    """把 float32 单声道采样([-1,1])编成 16bit PCM wav 并 base64,供 input_audio 上送。"""
    pcm = (np.clip(samples, -1.0, 1.0) * 32767.0).astype(np.int16)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(ONLINE_SAMPLE_RATE)
        wav_file.writeframes(pcm.tobytes())
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def _is_cjk(ch: str) -> bool:
    """是否中日韩统一表意文字(含扩展A)——按单字计伪词时间权重。"""
    code = ord(ch)
    return 0x3400 <= code <= 0x9FFF or 0xF900 <= code <= 0xFAFF


def _build_pseudo_words(text: str, start: float, end: float) -> list:
    """为无时间戳的整段文本按字符权重线性插值出伪词级时间戳。

    在线 API 只返回纯文本,而后端规则召回完全建立在词级时间戳拼接的归一化文本之上
    (words 为空的段不参与匹配),因此必须补出伪词。切词规则:CJK 逐字一词、
    连续的非 CJK 非空白字符(拉丁词/数字/标点)合为一词,按词内字符数占比在
    [start, end] 区间内线性分布。片段由 VAD 按静音切出(默认上限 28s),
    线性近似的误差与片段时长成正比,足够支撑时间轴定位与剪辑留白。
    """
    tokens = []
    pending = ""
    for ch in text:
        if ch.isspace():
            if pending:
                tokens.append(pending)
                pending = ""
        elif _is_cjk(ch):
            if pending:
                tokens.append(pending)
                pending = ""
            tokens.append(ch)
        else:
            pending += ch
    if pending:
        tokens.append(pending)
    if not tokens:
        return []

    total_chars = sum(len(token) for token in tokens)
    duration = max(end - start, 0.0)
    words = []
    consumed = 0
    for token in tokens:
        token_start = start + duration * (consumed / total_chars)
        consumed += len(token)
        token_end = start + duration * (consumed / total_chars)
        words.append({"word": token, "start": float(token_start), "end": float(token_end)})
    return words


def _retry_wait_seconds(attempt: int, retry_after: str) -> float:
    """计算第 attempt 次重试前的等待秒数:优先尊重 Retry-After 响应头,否则指数退避。"""
    if retry_after:
        try:
            return min(max(float(retry_after), 0.0), ONLINE_RETRY_MAX_WAIT_SECONDS)
        except ValueError:
            pass
    return min(ONLINE_RETRY_BASE_SECONDS * (2 ** attempt), ONLINE_RETRY_MAX_WAIT_SECONDS)


def _recognize_chunk_online(endpoint: str, api_key: str, model_name: str, samples: np.ndarray) -> str:
    """单个语音片段上送在线 ASR,返回识别文本。

    429 限流、5xx、网络抖动按指数退避重试(最多 ONLINE_MAX_RETRIES 次,优先尊重
    Retry-After 响应头);重试耗尽或其余 4xx(配置/鉴权类错误,重试无意义)抛 502。
    """
    payload = {
        "model": model_name,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {"data": "data:audio/wav;base64," + _encode_wav_base64(samples)},
                    }
                ],
            }
        ],
        "asr_options": {"language": "auto"},
    }
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    last_error = ""
    for attempt in range(ONLINE_MAX_RETRIES + 1):
        try:
            # Credentials and audio must never follow an untrusted redirect to another host.
            response = requests.post(
                endpoint,
                json=payload,
                headers=headers,
                timeout=ONLINE_TIMEOUT_SECONDS,
                allow_redirects=False,
            )
        except requests.RequestException as exc:
            last_error = f"在线 ASR 请求失败: {type(exc).__name__}: {exc}"
            if attempt < ONLINE_MAX_RETRIES:
                time.sleep(_retry_wait_seconds(attempt, ""))
                continue
            break

        if response.status_code == 429 or response.status_code >= 500:
            last_error = f"在线 ASR 服务返回 HTTP {response.status_code}: {response.text[:500]}"
            if attempt < ONLINE_MAX_RETRIES:
                time.sleep(_retry_wait_seconds(attempt, response.headers.get("Retry-After", "")))
                continue
            break
        if response.status_code < 200 or response.status_code >= 300:
            raise HTTPException(
                status_code=502,
                detail=f"在线 ASR 服务返回 HTTP {response.status_code}: {response.text[:500]}",
            )
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise HTTPException(status_code=502, detail=f"在线 ASR 响应结构异常: {exc}: {response.text[:500]}")
        return content or ""

    raise HTTPException(status_code=502, detail=f"{last_error}（已重试 {ONLINE_MAX_RETRIES} 次）")


def _transcribe_online(audio_path: str, base_url: str, api_key: str, model_name: str) -> list:
    """在线转写主流程:解码音频 → Silero VAD 按静音切片 → 并发逐片上送 → 组装 segments。

    与音频腿「失败即任务失败」的语义一致:任意片段识别失败都直接抛出,不做降级。
    返回结构与本地 faster-whisper 路径完全相同。
    """
    if not (api_key or "").strip():
        raise HTTPException(status_code=400, detail="在线 ASR 未配置 API Key")
    if not (model_name or "").strip():
        raise HTTPException(status_code=400, detail="在线 ASR 未配置模型名")
    endpoint = _online_endpoint(base_url)

    audio = decode_audio(audio_path, sampling_rate=ONLINE_SAMPLE_RATE)
    vad_options = VadOptions(
        max_speech_duration_s=ONLINE_MAX_SPEECH_SECONDS,
        min_silence_duration_ms=ONLINE_MIN_SILENCE_MS,
        speech_pad_ms=ONLINE_SPEECH_PAD_MS,
    )
    chunks = get_speech_timestamps(audio, vad_options, sampling_rate=ONLINE_SAMPLE_RATE)
    if not chunks:
        return []

    def _recognize(chunk: dict) -> str:
        return _recognize_chunk_online(endpoint, api_key.strip(), model_name.strip(), audio[chunk["start"]:chunk["end"]])

    with ThreadPoolExecutor(max_workers=min(ONLINE_CONCURRENCY, len(chunks))) as executor:
        texts = list(executor.map(_recognize, chunks))

    output = []
    for chunk, raw_text in zip(chunks, texts):
        text = normalize_text(raw_text)
        if not text:
            continue
        start = chunk["start"] / ONLINE_SAMPLE_RATE
        end = chunk["end"] / ONLINE_SAMPLE_RATE
        output.append(
            {
                "start": float(start),
                "end": float(end),
                "text": text,
                "words": _build_pseudo_words(text, start, end),
            }
        )
    return output


async def save_upload_to_temp(file: UploadFile, fallback_name: str) -> str:
    """把上传音频分块(1MB)流式写入临时文件,返回临时文件路径。delete=False,由调用方 unlink。"""
    suffix = Path(file.filename or fallback_name).suffix or Path(fallback_name).suffix
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            temp_file.write(chunk)
        return temp_file.name


@app.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    word_timestamps: bool = True,
    provider: str = Form("local"),
    online_base_url: str = Form(""),
    online_api_key: str = Form(""),
    online_model: str = Form(""),
):
    """转写入口:上传音频 → 落临时文件 → 线程池阻塞推理 → 返回带词级时间戳的 segments。

    响应结构与原 openai-whisper 版严格一致(后端 WhisperAsrClient 据此反序列化):
    ``{"segments": [{start, end, text, words: [{word, start, end}]}]}``。

    ``provider=online`` 时切换到在线 ASR(OpenAI Chat Completions 兼容,如 MiMo):
    端点/密钥/模型由后端按运行时设置以 form 字段透传,本地 Whisper 模型不加载;
    其余取值(含缺省 ``local``)走本地 faster-whisper 推理。

    faster-whisper 的 transcribe 返回 (segments 生成器, info),迭代生成器才真正解码推理;
    用 run_in_threadpool 卸载阻塞推理,_model_infer_lock 串行化。所有段/词文本经简繁转换。
    """
    temp_path = await save_upload_to_temp(file, "audio.wav")
    try:
        if (provider or "").strip().lower() == "online":
            output = await run_in_threadpool(
                _transcribe_online, temp_path, online_base_url, online_api_key, online_model
            )
            return {"segments": output}

        def _run_blocking_transcribe():
            with _model_infer_lock:
                segments_gen, _info = get_model().transcribe(
                    temp_path,
                    task="transcribe",
                    language=WHISPER_LANGUAGE,
                    initial_prompt=INITIAL_PROMPT,
                    beam_size=WHISPER_BEAM_SIZE if WHISPER_BEAM_SIZE > 0 else 1,
                    word_timestamps=word_timestamps,
                )
                # segments 是惰性生成器,list() 触发真正的转写计算
                return list(segments_gen)

        segments = await run_in_threadpool(_run_blocking_transcribe)
        output = []
        for segment in segments:
            words = []
            for word in (getattr(segment, "words", None) or []):
                words.append(
                    {
                        "word": normalize_text(word.word),
                        "start": float(word.start if word.start is not None else segment.start),
                        "end": float(word.end if word.end is not None else segment.end),
                    }
                )
            output.append(
                {
                    "start": float(segment.start),
                    "end": float(segment.end),
                    "text": normalize_text(segment.text),
                    "words": words,
                }
            )
        return {"segments": output}
    finally:
        Path(temp_path).unlink(missing_ok=True)


if __name__ == "__main__":
    import uvicorn

    # 直接 python app.py 启动,默认端口需与后端 app.asr.base-url 一致(9000)。
    # host/port 可用 ASR_HOST / ASR_PORT 覆盖。
    host = os.getenv("ASR_HOST", "127.0.0.1")
    port = int(os.getenv("ASR_PORT", "9000"))
    uvicorn.run(app, host=host, port=port)
