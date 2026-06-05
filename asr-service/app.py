"""ASR 语音转写微服务(FastAPI + openai-whisper / torch)。

四进程架构中的 ``asr-service``,默认监听 ``127.0.0.1:9000``,由 Java 后端经
``app.asr.base-url`` 通过 HTTP 调用:接收后端用 FFmpeg 抽取的 16k mono 音频,
用 Whisper 转写为带词级时间戳的 segments 返回(供后端做规则召回与时间轴定位)。

本进程允许引入 torch(Whisper 依赖 torch 做推理),与 ``ocr-service`` 完全独立的
原因:torch(CUDA 13)与 paddlepaddle-gpu(CUDA 12)共用同名 cuDNN 动态库,
同进程加载会崩溃,故 ASR(torch)与 OCR(paddle)各跑一个独立进程、独立 venv。
"""

import os
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from opencc import OpenCC
import whisper


# Whisper 模型规格:默认 large-v3-turbo(精度/速度折中);可用 WHISPER_MODEL 覆盖。
MODEL_SIZE = os.getenv("WHISPER_MODEL", "large-v3-turbo")
# 设备选择:默认 'GPU' 仅是用户友好别名,下方统一规范化为 torch 设备串(cuda/cpu)。
DEVICE = os.getenv("WHISPER_DEVICE", "GPU")
# 规范化为 PyTorch 设备字符串:GPU/gpu/cuda -> cuda;cuda:N 保留;其余 -> cpu
_device_lower = DEVICE.strip().lower()
if _device_lower in {"gpu", "cuda"}:
    DEVICE = "cuda"
elif _device_lower.startswith("cuda:"):
    DEVICE = _device_lower
else:
    DEVICE = "cpu"
FP16 = os.getenv("WHISPER_FP16", "true").lower() in {"1", "true", "yes", "on"}
# OpenCC 简繁转换配置名:默认 t2s(繁转简),空串则不做转换。
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
# 引导提示:让模型倾向输出简体中文普通话内容。
INITIAL_PROMPT = os.getenv("WHISPER_INITIAL_PROMPT", "请使用简体中文转写普通话内容。")
# beam search:默认 5,数字/口语(如"几十块"易被听成"十块")识别更准;设 0 改用贪心解码,更快(CPU 上明显)
WHISPER_BEAM_SIZE = int(os.getenv("WHISPER_BEAM_SIZE", "5"))
# FFmpeg 二进制目录:Whisper 内部解码音频需要 ffmpeg 在 PATH 上;
# 未显式指定时回退到仓库内置的 backend/tools/ffmpeg/bin。
FFMPEG_BIN_DIR = os.getenv("FFMPEG_BIN_DIR")

if FFMPEG_BIN_DIR:
    os.environ["PATH"] = FFMPEG_BIN_DIR + os.pathsep + os.environ.get("PATH", "")
else:
    bundled_ffmpeg = Path(__file__).resolve().parents[1] / "backend" / "tools" / "ffmpeg" / "bin"
    if bundled_ffmpeg.exists():
        os.environ["PATH"] = str(bundled_ffmpeg) + os.pathsep + os.environ.get("PATH", "")

app = FastAPI(title="OpenAI Whisper ASR Service")
model = None
converter = OpenCC(CHINESE_CONVERTER) if CHINESE_CONVERTER else None
# 初始化锁:保护全局 Whisper 模型的懒加载(double-checked locking)。
_model_lock = threading.Lock()
# 推理锁:同一个全局 Whisper 模型不保证并发推理安全(多任务并行时),故串行化。
_model_infer_lock = threading.Lock()


def _probe_gpu() -> dict:
    """探测并返回 torch 的 CUDA 自检信息,供 /health 暴露,辅助排查 GPU 是否生效。

    本进程只负责 Whisper(torch);OCR 已拆到独立的 ocr_app(端口 9001)。
    任何探测异常都不抛出,仅记录到返回字典的 torchError 字段,保证健康检查不被 GPU 问题拖垮。
    """
    # 探测 torch 的 CUDA 状态。本进程只负责 Whisper(torch);OCR 已拆到独立的 ocr_app(端口 9001)。
    info = {}
    try:
        import torch
        cuda_ok = bool(torch.cuda.is_available())
        info["torchVersion"] = torch.__version__
        info["torchCudaAvailable"] = cuda_ok
        info["torchCudaRuntime"] = getattr(torch.version, "cuda", None)
        if cuda_ok:
            info["torchDeviceName"] = torch.cuda.get_device_name(0)
            info["torchCapability"] = ".".join(map(str, torch.cuda.get_device_capability(0)))
        try:
            arch = torch.cuda.get_arch_list()
            info["torchArchList"] = arch
            info["torchHasSm120"] = any("120" in a for a in arch)
        except Exception:
            # get_arch_list 异常时同时回填两个键,保持 /health 返回结构稳定(消费方无需做缺键容错)
            info["torchArchList"] = None
            info["torchHasSm120"] = None
    except Exception as exc:
        info["torchError"] = f"{type(exc).__name__}: {exc}"
    return info


@app.get("/health")
async def health():
    """健康检查:返回模型规格、目标设备、fp16 开关、模型是否已加载,以及 GPU 自检信息。

    被后端/运维用来确认 ASR 服务存活及 GPU 是否生效(看 gpu.torchCudaAvailable)。
    """
    return {
        "status": "ok",
        "model": MODEL_SIZE,
        "device": DEVICE,
        "fp16": FP16,
        "modelLoaded": model is not None,
        "gpu": _probe_gpu(),
    }


def normalize_text(text: str) -> str:
    """去首尾空白并按 CHINESE_CONVERTER 做简繁转换(默认 t2s 繁转简)。

    未配置转换器(CHINESE_CONVERTER 为空)时仅做 strip,不改字形。

    :param text: 待规范化的原始转写文本(段文本或词文本)
    :return: 规范化后的文本
    """
    value = text.strip()
    return converter.convert(value) if converter else value


def get_model():
    """double-checked locking 懒加载全局 Whisper 模型,返回已加载实例。

    DEVICE 为 cuda 时先校验 torch.cuda 是否可用:不可用直接抛 503 快速失败
    (而非静默回退 CPU),避免误用 CPU 版 torch 跑出极慢且无提示的转写。
    """
    global model
    if model is None:
        with _model_lock:
            if model is None:
                if DEVICE.startswith("cuda"):
                    import torch
                    if not torch.cuda.is_available():
                        raise HTTPException(
                            status_code=503,
                            detail=(
                                "WHISPER_DEVICE=cuda 但 torch.cuda.is_available() 为 False,"
                                "当前 venv 很可能装的是 CPU 版 PyTorch(版本号带 +cpu)。"
                                "请运行 asr-service/install-gpu.bat 重装,或手动执行 "
                                "pip install torch==2.12.0 --index-url https://download.pytorch.org/whl/cu130;"
                                "验证 python -c \"import torch;print(torch.__version__,torch.cuda.is_available())\" 应为 2.12.0+cu130 True。"
                                "更多详情见 /health 的 gpu 字段。"
                            ),
                        )
                model = whisper.load_model(MODEL_SIZE, device=DEVICE)
    return model


async def save_upload_to_temp(file: UploadFile, fallback_name: str) -> str:
    """把上传音频分块(1MB)流式写入临时文件,返回临时文件路径。

    suffix 优先取原文件名后缀,否则回退 fallback_name 的后缀(供 Whisper 按扩展名识别格式)。
    delete=False:临时文件不自动删除,由调用方在 finally 中 unlink。

    :param file: FastAPI 上传文件对象
    :param fallback_name: 原文件名缺失时用于取后缀的兜底文件名
    :return: 写入完成的临时文件绝对路径
    """
    suffix = Path(file.filename or fallback_name).suffix or Path(fallback_name).suffix
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            temp_file.write(chunk)
        return temp_file.name


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), word_timestamps: bool = True):
    """转写入口:上传音频 → 落临时文件 → 卸载到线程池做阻塞推理 → 返回带词级时间戳的 segments。

    用 run_in_threadpool 把 CPU/GPU 密集的阻塞推理移出事件循环,避免阻塞异步 I/O;
    推理本身经 _model_infer_lock 串行化(同一全局模型不保证并发推理安全)。
    所有段/词文本经 normalize_text 做简繁转换;无论成败 finally 必删临时文件。

    :param file: 后端抽取的 16k mono 音频
    :param word_timestamps: 是否输出词级时间戳(默认 True,后端靠它做时间轴定位)
    :return: ``{"segments": [{start, end, text, words: [{word, start, end}]}]}``
    """
    temp_path = await save_upload_to_temp(file, "audio.wav")
    try:
        def _run_blocking_transcribe():
            # 同一全局 Whisper 模型不保证并发推理安全(多任务并行时),加推理锁串行化。
            with _model_infer_lock:
                return get_model().transcribe(
                    temp_path,
                    task="transcribe",
                    initial_prompt=INITIAL_PROMPT,
                    language=os.getenv("WHISPER_LANGUAGE") or None,
                    word_timestamps=word_timestamps,
                    fp16=FP16,
                    beam_size=WHISPER_BEAM_SIZE if WHISPER_BEAM_SIZE > 0 else None,
                    verbose=False,
                )

        result = await run_in_threadpool(_run_blocking_transcribe)
        output = []
        for segment in result.get("segments", []):
            words = []
            for word in segment.get("words") or []:
                words.append(
                    {
                        "word": normalize_text(word.get("word", "")),
                        "start": float(word.get("start", segment.get("start", 0.0))),
                        "end": float(word.get("end", segment.get("end", 0.0))),
                    }
                )
            output.append(
                {
                    "start": float(segment.get("start", 0.0)),
                    "end": float(segment.get("end", 0.0)),
                    "text": normalize_text(segment.get("text", "")),
                    "words": words,
                }
            )
        return {"segments": output}
    finally:
        Path(temp_path).unlink(missing_ok=True)


if __name__ == "__main__":
    import uvicorn

    # 直接 python app.py 启动时使用,默认端口需与后端 app.asr.base-url 一致(9000)。
    # host/port 可用 ASR_HOST / ASR_PORT 覆盖。
    host = os.getenv("ASR_HOST", "127.0.0.1")
    port = int(os.getenv("ASR_PORT", "9000"))
    uvicorn.run(app, host=host, port=port)
