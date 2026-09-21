"""ASR 语音转写微服务(FastAPI + faster-whisper / CTranslate2,纯 CPU 轻量版)。

四进程架构中的 ``asr-service``,默认监听 ``127.0.0.1:9000``,由 Java 后端经
``app.asr.base-url`` 通过 HTTP 调用:接收后端用 FFmpeg 抽取的 16k mono 音频,
转写为带词级时间戳的 segments 返回(供后端做规则召回与时间轴定位)。

为适配只有核显(无 NVIDIA/CUDA)的机器,本服务用 faster-whisper(CTranslate2 引擎)
在纯 CPU 上以 INT8 量化推理:比 openai-whisper 约快 4 倍、内存更省,且不依赖 torch/CUDA。
对外 HTTP 接口(/、/health、/transcribe)与原 openai-whisper 版完全一致,后端无需改动。

``/transcribe`` 只使用本地 faster-whisper，并返回引擎生成的段级和词级时间戳。
这样规则命中、时间轴定位和剪辑建议都基于真实模型时间戳，不接受没有可靠时间轴的远程转写结果。
"""

import logging
import os
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from faster_whisper import WhisperModel

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
):
    """转写入口:上传音频 → 落临时文件 → 线程池阻塞推理 → 返回带词级时间戳的 segments。

    响应结构与原 openai-whisper 版严格一致(后端 WhisperAsrClient 据此反序列化):
    ``{"segments": [{start, end, text, words: [{word, start, end}]}]}``。

    faster-whisper 的 transcribe 返回 (segments 生成器, info),迭代生成器才真正解码推理;
    用 run_in_threadpool 卸载阻塞推理,_model_infer_lock 串行化。所有段/词文本经简繁转换。
    """
    temp_path = await save_upload_to_temp(file, "audio.wav")
    try:
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
