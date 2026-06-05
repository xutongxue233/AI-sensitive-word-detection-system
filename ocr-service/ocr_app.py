"""画面硬字幕 OCR 独立服务(PaddleOCR, 默认端口 9001)。

与 Whisper(ASR, 端口 9000)拆成两个进程:本进程只 import paddle(经 ocr_core),
绝不加载 torch,从而避免 paddle 的 CUDA 12.9 与 torch 的 CUDA 13 运行时在同进程冲突,
paddle 即可正常使用 GPU。后端 app.subtitle-ocr.base-url 应指向本服务。
"""

import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.concurrency import run_in_threadpool

import ocr_core


app = FastAPI(title="PaddleOCR Subtitle Service")


@app.get("/health")
async def health():
    """健康探针:供后端探活并据此在前端展示 OCR 服务状态。

    返回 OCR 语言/版本、是否启用 GPU、模型是否已加载,以及对 paddle CUDA 的探测结果
    (是否编译了 CUDA、可用 GPU 数等)。返回字段刻意只覆盖 paddle 一侧——本进程不加载
    torch,故不探测 torch CUDA,以维持与 ASR 进程的 CUDA 运行时隔离。
    """
    return {
        "status": "ok",
        "ocrLang": ocr_core.PADDLE_OCR_LANG,
        "ocrVersion": ocr_core.PADDLE_OCR_VERSION or "(default)",
        "paddleOcrUseGpu": ocr_core.PADDLE_OCR_USE_GPU,
        "ocrModelLoaded": ocr_core.ocr_model_loaded(),
        "gpu": ocr_core.probe_paddle_gpu(),
    }


async def save_upload_to_temp(file: UploadFile, fallback_name: str) -> str:
    """把上传文件以 1MB 分块流式写入临时文件,返回临时文件路径。

    分块读写而非一次性 read() 到内存,是为了避免大视频整体驻留内存。临时文件以
    delete=False 创建,故落盘后不会自动删除——调用方负责在用完后 unlink。

    :param file: FastAPI 上传文件对象。
    :param fallback_name: 当上传文件名缺失/无后缀时,用于推断临时文件后缀的兜底名。
    :return: 落盘后的临时文件绝对路径。
    """
    suffix = Path(file.filename or fallback_name).suffix or Path(fallback_name).suffix
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            temp_file.write(chunk)
        return temp_file.name


@app.post("/ocr-subtitles")
async def ocr_subtitles(
    file: UploadFile = File(...),
    interval_seconds: float = Form(0.75),
    crop_bottom_ratio: float = Form(0.35),
    min_confidence: float = Form(0.35),
    lang: str = Form(ocr_core.PADDLE_OCR_LANG),
):
    """画面硬字幕识别主入口:接收视频,返回逐条字幕 segment。

    把上传视频落盘为临时文件后逐帧采样做 OCR,识别底部硬字幕并归并为带时间轴/坐标框的
    segment 列表。阻塞型 OCR 通过 run_in_threadpool 移出事件循环,避免拖垮异步服务。
    finally 始终删除临时文件。

    :param file: 待识别的视频文件。
    :param interval_seconds: 采样间隔(秒),即每隔多久取一帧做 OCR;越小越准但越慢。
    :param crop_bottom_ratio: 只识别画面底部多大比例的区域(0~1),用于聚焦字幕带、减少干扰与开销。
    :param min_confidence: 置信度过滤阈值,低于此值的识别文本丢弃。
    :param lang: OCR 语言(默认取环境配置),决定加载哪套 PaddleOCR 模型。
    :return: {"segments": [...]} —— 每条含起止时间、文本、词级时间戳与归一化字幕框。
    """
    temp_path = await save_upload_to_temp(file, "video.mp4")
    try:
        segments = await run_in_threadpool(
            ocr_core.run_ocr_on_video,
            temp_path,
            interval_seconds,
            crop_bottom_ratio,
            min_confidence,
            lang,
        )
        return {"segments": segments}
    finally:
        Path(temp_path).unlink(missing_ok=True)


if __name__ == "__main__":
    import uvicorn

    # OCR 独立服务,默认端口 9001,需与后端 app.subtitle-ocr.base-url 一致。
    # host/port 可用 OCR_SERVICE_HOST / OCR_SERVICE_PORT 覆盖。
    host = os.getenv("OCR_SERVICE_HOST", "127.0.0.1")
    port = int(os.getenv("OCR_SERVICE_PORT", "9001"))
    uvicorn.run(app, host=host, port=port)
