"""
Video-Subtitle-Remover(VSR)包装服务。

为什么独立成服务:VSR 依赖 PyTorch 2.7 + PaddlePaddle 3.0,与 asr-service 的 paddlepaddle 2.6.2
直接冲突,必须跑在独立的 Python 环境里。本服务只暴露一个"处理单个视频片段的指定区域"的端点,
按时间段切片/拼接的编排由后端 Java 负责(因为只去违规命中的时间段)。

运行前提(用户准备):
  1. git clone https://github.com/YaoFANGUK/video-subtitle-remover
  2. 在其独立 venv 里按 VSR 的 requirements 安装依赖(PyTorch/paddlepaddle 3.0/onnxruntime 等),
     首次运行会下载 STTN/LAMA 等模型(GB 级)
  3. 在同一 venv 里额外安装本服务依赖: pip install -r vsr-service/requirements.txt
  4. 设置环境变量 VSR_HOME 指向 clone 的仓库根目录,然后启动:
     VSR_HOME=/path/to/video-subtitle-remover python vsr-service/app.py
  默认端口 9100(可用 VSR_SERVICE_PORT 覆盖)。无 NVIDIA GPU 时 VSR 自动回退 CPU(较慢)。
"""

import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

# VSR 仓库根目录:通过 VSR_HOME 指定,加入 sys.path 以便 import 其 backend 包
VSR_HOME = os.getenv("VSR_HOME", "").strip()
if VSR_HOME and VSR_HOME not in sys.path:
    sys.path.insert(0, VSR_HOME)

DEFAULT_INPAINT_MODE = os.getenv("VSR_INPAINT_MODE", "sttn_auto")

app = FastAPI(title="Video Subtitle Remover Service")

# 懒加载缓存:(SubtitleRemover 类, VSR config 模块, InpaintMode 枚举或 None)
_loaded = None


def _load_vsr():
    global _loaded
    if _loaded is not None:
        return _loaded
    if not VSR_HOME:
        raise HTTPException(status_code=503, detail="未设置 VSR_HOME 环境变量(应指向 video-subtitle-remover 仓库根目录)")
    if not Path(VSR_HOME).is_dir():
        raise HTTPException(status_code=503, detail=f"VSR_HOME 不是有效目录: {VSR_HOME}")
    try:
        from backend.main import SubtitleRemover
        from backend import config as vsr_config
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail=(
                f"导入 VSR 失败,请确认 VSR_HOME={VSR_HOME} 指向 video-subtitle-remover 根目录,"
                f"且其依赖已在当前 Python 环境安装。原始错误: {exc}"
            ),
        ) from exc
    inpaint_mode_enum = getattr(vsr_config, "InpaintMode", None)
    _loaded = (SubtitleRemover, vsr_config, inpaint_mode_enum)
    return _loaded


def _resolve_mode(inpaint_mode_enum, name: str):
    """把字符串模式名(如 sttn_auto / sttn-auto / STTN_AUTO)映射到 VSR 的 InpaintMode 枚举成员。"""
    if inpaint_mode_enum is None:
        return None
    key = (name or DEFAULT_INPAINT_MODE).strip().upper().replace("-", "_")
    try:
        return inpaint_mode_enum[key]
    except (KeyError, TypeError):
        return None


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "vsrHome": VSR_HOME,
        "loaded": _loaded is not None,
        "defaultInpaintMode": DEFAULT_INPAINT_MODE,
    }


@app.post("/remove-subtitle")
async def remove_subtitle(
    file: UploadFile = File(...),
    areas: str = Form(...),
    inpaint_mode: str = Form(DEFAULT_INPAINT_MODE),
):
    """
    对上传的视频片段,去除指定像素区域内的字幕,返回处理后的片段(已合并原音频)。
    - file: 视频片段(由后端按违规时间段切出)
    - areas: JSON 字符串,形如 [[ymin,ymax,xmin,xmax], ...](整数像素坐标,支持多区域)
    - inpaint_mode: sttn_auto(默认) / sttn_det / lama / propainter / opencv
    """
    subtitle_remover_cls, vsr_config, inpaint_mode_enum = _load_vsr()

    try:
        parsed = json.loads(areas)
        sub_areas = [tuple(int(round(float(v))) for v in area) for area in parsed if len(area) == 4]
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"areas 必须是 [[ymin,ymax,xmin,xmax],...] 的 JSON: {exc}") from exc
    if not sub_areas:
        raise HTTPException(status_code=400, detail="areas 为空,至少需要一个 [ymin,ymax,xmin,xmax] 区域")

    workdir = tempfile.mkdtemp(prefix="vsr-")
    suffix = Path(file.filename or "clip.mp4").suffix or ".mp4"
    in_path = os.path.join(workdir, "in" + suffix)
    out_path = os.path.join(workdir, "out.mp4")
    try:
        with open(in_path, "wb") as buffer:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                buffer.write(chunk)

        mode = _resolve_mode(inpaint_mode_enum, inpaint_mode)
        if mode is not None:
            try:
                vsr_config.inpaintMode.value = mode
            except Exception:
                pass  # 模式设置失败时使用 VSR 默认模式,不阻断处理

        def _run():
            remover = subtitle_remover_cls(in_path)
            remover.sub_areas = sub_areas
            remover.video_out_path = out_path
            remover.run()

        # VSR 推理是同步 CPU/GPU 密集型,丢到线程池避免阻塞事件循环
        await run_in_threadpool(_run)

        if not os.path.exists(out_path):
            raise HTTPException(status_code=502, detail="VSR 未生成输出文件")
        # 响应发送完成后再清理临时目录(含输入与输出)
        return FileResponse(
            out_path,
            media_type="video/mp4",
            filename="out.mp4",
            background=BackgroundTask(shutil.rmtree, workdir, ignore_errors=True),
        )
    except HTTPException:
        shutil.rmtree(workdir, ignore_errors=True)
        raise
    except Exception as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(status_code=502, detail=f"VSR 处理失败: {exc}") from exc


if __name__ == "__main__":
    import uvicorn

    host = os.getenv("VSR_SERVICE_HOST", "127.0.0.1")
    port = int(os.getenv("VSR_SERVICE_PORT", "9100"))
    uvicorn.run(app, host=host, port=port)
