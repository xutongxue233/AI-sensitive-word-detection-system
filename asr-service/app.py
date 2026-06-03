import os
import re
import shutil
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from opencc import OpenCC
import whisper


MODEL_SIZE = os.getenv("WHISPER_MODEL", "large-v3")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
FP16 = os.getenv("WHISPER_FP16", "false").lower() in {"1", "true", "yes", "on"}
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
INITIAL_PROMPT = os.getenv("WHISPER_INITIAL_PROMPT", "请使用简体中文转写普通话内容。")
# beam search:默认 5,数字/口语(如"几十块"易被听成"十块")识别更准;设 0 改用贪心解码,更快(CPU 上明显)
WHISPER_BEAM_SIZE = int(os.getenv("WHISPER_BEAM_SIZE", "5"))
FFMPEG_BIN_DIR = os.getenv("FFMPEG_BIN_DIR")
PADDLE_OCR_LANG = os.getenv("PADDLE_OCR_LANG", "ch")
PADDLE_OCR_VERSION = os.getenv("PADDLE_OCR_VERSION", "PP-OCRv4")
PADDLE_OCR_DET_MODEL = os.getenv("PADDLE_OCR_DET_MODEL")
PADDLE_OCR_REC_MODEL = os.getenv("PADDLE_OCR_REC_MODEL")
PADDLE_OCR_USE_GPU = os.getenv("PADDLE_OCR_USE_GPU", "false").lower() in {"1", "true", "yes", "on"}
PADDLE_OCR_ENABLE_MKLDNN = os.getenv("PADDLE_OCR_ENABLE_MKLDNN", "false").lower() in {"1", "true", "yes", "on"}

if not PADDLE_OCR_ENABLE_MKLDNN:
    os.environ.setdefault("FLAGS_use_mkldnn", "0")
    os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "0")

if FFMPEG_BIN_DIR:
    os.environ["PATH"] = FFMPEG_BIN_DIR + os.pathsep + os.environ.get("PATH", "")
else:
    bundled_ffmpeg = Path(__file__).resolve().parents[1] / "backend" / "tools" / "ffmpeg" / "bin"
    if bundled_ffmpeg.exists():
        os.environ["PATH"] = str(bundled_ffmpeg) + os.pathsep + os.environ.get("PATH", "")

app = FastAPI(title="OpenAI Whisper ASR Service")
model = None
converter = OpenCC(CHINESE_CONVERTER) if CHINESE_CONVERTER else None
ocr_reader = None
ocr_reader_lang = None
# 初始化锁:仅保护两个全局模型的懒加载(double-checked locking)。
_model_lock = threading.Lock()
_ocr_lock = threading.Lock()
# 推理锁:同一个全局模型对象不保证并发推理安全(PaddlePaddle 预测器/Whisper 单实例),
# 故对同类推理串行化;Whisper 与 PaddleOCR 用不同锁,单个检测任务的音频腿与画面腿仍可并行。
_model_infer_lock = threading.Lock()
_ocr_infer_lock = threading.Lock()


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model": MODEL_SIZE,
        "device": DEVICE,
        "fp16": FP16,
        "modelLoaded": model is not None,
        "ocrModelLoaded": ocr_reader is not None,
    }


def normalize_text(text: str) -> str:
    value = text.strip()
    return converter.convert(value) if converter else value


def get_model():
    global model
    if model is None:
        with _model_lock:
            if model is None:
                model = whisper.load_model(MODEL_SIZE, device=DEVICE)
    return model


async def save_upload_to_temp(file: UploadFile, fallback_name: str) -> str:
    suffix = Path(file.filename or fallback_name).suffix or Path(fallback_name).suffix
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            temp_file.write(chunk)
        return temp_file.name


def get_ocr_reader(lang: str):
    global ocr_reader, ocr_reader_lang
    selected_lang = (lang or PADDLE_OCR_LANG or "ch").strip()
    cache_key = "|".join([
        selected_lang,
        PADDLE_OCR_VERSION or "",
        PADDLE_OCR_DET_MODEL or "",
        PADDLE_OCR_REC_MODEL or "",
    ])
    if ocr_reader is None or ocr_reader_lang != cache_key:
        with _ocr_lock:
            if ocr_reader is None or ocr_reader_lang != cache_key:
                try:
                    from paddleocr import PaddleOCR
                except Exception as exc:
                    raise HTTPException(
                        status_code=503,
                        detail=f"PaddleOCR 依赖未安装或不可用，请安装 asr-service/requirements.txt: {exc}",
                    ) from exc
                cleanup_incomplete_paddlex_models()
                try:
                    reader = create_paddle_ocr(PaddleOCR, selected_lang)
                except Exception as exc:
                    cleanup_incomplete_paddlex_models()
                    try:
                        reader = create_paddle_ocr(PaddleOCR, selected_lang)
                    except Exception as retry_exc:
                        raise HTTPException(
                            status_code=503,
                            detail=(
                                "PaddleOCR 模型初始化失败。系统已尝试清理不完整模型缓存；"
                                f"请确认网络可下载模型，或手动删除 {Path.home() / '.paddlex' / 'official_models'} 后重试。"
                                f"原始错误: {retry_exc or exc}"
                            ),
                        ) from retry_exc
                ocr_reader = reader
                ocr_reader_lang = cache_key
    return ocr_reader


def create_paddle_ocr(PaddleOCR, lang: str):
    try:
        return PaddleOCR(
            use_angle_cls=True,
            lang=lang,
            use_gpu=PADDLE_OCR_USE_GPU,
            enable_mkldnn=PADDLE_OCR_ENABLE_MKLDNN,
            show_log=False,
            ocr_version=PADDLE_OCR_VERSION,
        )
    except TypeError:
        pass

    kwargs = {
        "lang": lang,
        "ocr_version": PADDLE_OCR_VERSION,
        "use_doc_orientation_classify": False,
        "use_doc_unwarping": False,
        "use_textline_orientation": False,
    }
    if PADDLE_OCR_DET_MODEL:
        kwargs["text_detection_model_name"] = PADDLE_OCR_DET_MODEL
    if PADDLE_OCR_REC_MODEL:
        kwargs["text_recognition_model_name"] = PADDLE_OCR_REC_MODEL
    try:
        return PaddleOCR(**kwargs)
    except TypeError:
        try:
            return PaddleOCR(
                use_angle_cls=True,
                lang=lang,
                use_gpu=PADDLE_OCR_USE_GPU,
                enable_mkldnn=PADDLE_OCR_ENABLE_MKLDNN,
                show_log=False,
            )
        except TypeError:
            return PaddleOCR(lang=lang)


def cleanup_incomplete_paddlex_models():
    model_root = Path.home() / ".paddlex" / "official_models"
    if not model_root.exists():
        return
    for model_dir in model_root.iterdir():
        if not model_dir.is_dir():
            continue
        has_model_config = (model_dir / "inference.json").exists() or (model_dir / "inference.yml").exists()
        has_weights = (model_dir / "inference.pdiparams").exists()
        has_partial_weights = (model_dir / ".cache" / "huggingface" / "download" / "inference.pdiparams.metadata").exists()
        if has_model_config and has_partial_weights and not has_weights:
            shutil.rmtree(model_dir, ignore_errors=True)


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), word_timestamps: bool = True):
    temp_path = await save_upload_to_temp(file, "audio.wav")
    try:
        def _transcribe():
            # 同一全局 Whisper 模型不保证并发推理安全(多任务并行时),加推理锁串行化;
            # 与 OCR 用不同锁,单个任务的音频腿与画面腿仍可并行。
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

        result = await run_in_threadpool(_transcribe)
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


@app.post("/ocr-subtitles")
async def ocr_subtitles(
    file: UploadFile = File(...),
    interval_seconds: float = Form(0.75),
    crop_bottom_ratio: float = Form(0.35),
    min_confidence: float = Form(0.35),
    lang: str = Form(PADDLE_OCR_LANG),
):
    temp_path = await save_upload_to_temp(file, "video.mp4")
    try:
        def _ocr():
            # 同一全局 PaddleOCR 预测器不保证并发推理安全(多任务并行时),加推理锁串行化;
            # 与 Whisper 用不同锁,单个任务的画面腿与音频腿仍可并行。
            with _ocr_infer_lock:
                return recognize_video_subtitles(
                    temp_path,
                    get_ocr_reader(lang),
                    interval_seconds=max(0.2, interval_seconds),
                    crop_bottom_ratio=min(1.0, max(0.05, crop_bottom_ratio)),
                    min_confidence=min(1.0, max(0.0, min_confidence)),
                )

        segments = await run_in_threadpool(_ocr)
        return {"segments": segments}
    finally:
        Path(temp_path).unlink(missing_ok=True)


def recognize_video_subtitles(
    video_path: str,
    reader,
    interval_seconds: float,
    crop_bottom_ratio: float,
    min_confidence: float,
):
    try:
        import cv2
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail=f"OCR 视频处理依赖不可用，请安装 opencv-python-headless: {exc}",
        ) from exc

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise HTTPException(status_code=400, detail="无法打开视频文件进行字幕 OCR")
    try:
        fps = float(cap.get(cv2.CAP_PROP_FPS) or 0) or 25.0
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        duration = frame_count / fps if frame_count > 0 else 0.0
        if duration <= 0:
            duration = probe_video_duration_by_reading(cap, fps)
            cap.set(cv2.CAP_PROP_POS_FRAMES, 0)

        segments = []
        active = None
        t = 0.0
        while t <= duration + 0.001:
            cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
            ok, frame = cap.read()
            if not ok:
                break
            subtitle = read_subtitle_text(reader, frame, crop_bottom_ratio, min_confidence, cv2)
            active = update_ocr_segments(segments, active, subtitle, t, min(duration, t + interval_seconds))
            t += interval_seconds
        if active is not None:
            segments.append(to_segment(active))
        return segments
    finally:
        cap.release()


def probe_video_duration_by_reading(cap, fps: float) -> float:
    frames = 0
    while True:
        ok, _ = cap.read()
        if not ok:
            break
        frames += 1
    return frames / max(1.0, fps)


def read_subtitle_text(reader, frame, crop_bottom_ratio: float, min_confidence: float, cv2) -> dict:
    height, width = frame.shape[:2]
    crop_height = max(1, int(height * crop_bottom_ratio))
    crop_top = max(0, height - crop_height)
    crop = frame[crop_top:height, 0:width]
    if crop.size == 0:
        return {"text": ""}

    scale = min(2.0, max(1.0, 1280 / max(1, width)))
    if scale > 1.01:
        crop = cv2.resize(crop, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)

    try:
        raw_results = run_paddle_ocr(reader, crop, cv2)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"字幕 OCR 识别失败: {exc}") from exc

    items = []
    for bbox, text, confidence in raw_results:
        text = cleanup_ocr_text(text)
        if confidence >= min_confidence and text:
            items.append((bbox_position(bbox), text, bbox))
    items.sort(key=lambda value: value[0])
    text = cleanup_ocr_text(" ".join(text for _, text, _ in items))
    if not text:
        return {"text": ""}
    box = union_bbox([bbox for _, _, bbox in items], width * scale, crop_height * scale, crop_top, scale, width, height)
    return {"text": text, **box}


def run_paddle_ocr(reader, crop, cv2) -> list[tuple[list, str, float]]:
    with tempfile.NamedTemporaryFile(delete=False, suffix=".jpg") as image_file:
        image_path = image_file.name
    try:
        cv2.imwrite(image_path, crop)
        if hasattr(reader, "predict"):
            try:
                return parse_paddle_v3_results(reader.predict(input=image_path))
            except TypeError:
                return parse_paddle_v3_results(reader.predict(image_path))
        return parse_paddle_v2_results(reader.ocr(image_path, cls=True))
    finally:
        Path(image_path).unlink(missing_ok=True)


def parse_paddle_v2_results(results) -> list[tuple[list, str, float]]:
    items = []
    for page in results or []:
        if not page:
            continue
        for line in page:
            if not line or len(line) < 2:
                continue
            bbox = line[0]
            text_score = line[1]
            if not text_score or len(text_score) < 2:
                continue
            items.append((bbox, str(text_score[0]), float(text_score[1] or 0)))
    return items


def parse_paddle_v3_results(results) -> list[tuple[list, str, float]]:
    items = []
    for result in results or []:
        data = paddle_result_to_dict(result)
        payload = data.get("res", data) if isinstance(data, dict) else {}
        texts = payload.get("rec_texts") or payload.get("texts") or []
        scores = payload.get("rec_scores") or payload.get("scores") or []
        boxes = payload.get("rec_polys") or payload.get("dt_polys") or payload.get("boxes") or []
        for index, text in enumerate(texts):
            score = scores[index] if index < len(scores) else 1.0
            box = boxes[index] if index < len(boxes) else []
            items.append((box, str(text), float(score or 0)))
    return items


def paddle_result_to_dict(result) -> dict:
    if isinstance(result, dict):
        return result
    value = getattr(result, "json", None)
    if callable(value):
        value = value()
    if isinstance(value, dict):
        return value
    to_dict = getattr(result, "to_dict", None)
    if callable(to_dict):
        value = to_dict()
        if isinstance(value, dict):
            return value
    return {}


def bbox_position(bbox) -> tuple[float, float]:
    points = bbox or []
    xs = [float(point[0]) for point in points if len(point) >= 2]
    ys = [float(point[1]) for point in points if len(point) >= 2]
    if not xs or not ys:
        return (0.0, 0.0)
    return (sum(ys) / len(ys), sum(xs) / len(xs))


def union_bbox(items: list, scaled_width: float, scaled_height: float, crop_top: int, scale: float, width: int, height: int) -> dict:
    points = []
    for bbox in items:
        for point in bbox or []:
            if len(point) >= 2:
                points.append((float(point[0]), float(point[1])))
    if not points:
        return {"bboxX": 0.08, "bboxY": 0.74, "bboxWidth": 0.84, "bboxHeight": 0.16}
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    x1 = max(0.0, min(xs) / max(1.0, scaled_width))
    x2 = min(1.0, max(xs) / max(1.0, scaled_width))
    y1 = max(0.0, (crop_top + min(ys) / max(1.0, scale)) / max(1, height))
    y2 = min(1.0, (crop_top + max(ys) / max(1.0, scale)) / max(1, height))
    return {
        "bboxX": x1,
        "bboxY": y1,
        "bboxWidth": max(0.02, x2 - x1),
        "bboxHeight": max(0.02, y2 - y1),
    }


def cleanup_ocr_text(text: str) -> str:
    value = normalize_text(text or "")
    value = value.replace("\u3000", " ")
    value = re.sub(r"\s+", " ", value).strip()
    return value


def update_ocr_segments(segments: list, active: dict | None, subtitle: dict, start: float, end: float):
    text = subtitle.get("text", "")
    key = normalize_for_group(text)
    if not key:
        if active is not None:
            segments.append(to_segment(active))
        return None

    if active is not None and same_subtitle_key(active["key"], key):
        active["end"] = max(active["end"], end)
        if len(text) > len(active["text"]):
            active["text"] = text
        active["bbox"] = merge_bbox(active.get("bbox"), subtitle)
        return active

    if active is not None:
        segments.append(to_segment(active))
    return {"key": key, "text": text, "bbox": subtitle, "start": start, "end": max(end, start + 0.2)}


def merge_bbox(current: dict | None, next_box: dict) -> dict:
    if not current or "bboxX" not in current:
        return next_box
    if not next_box or "bboxX" not in next_box:
        return current
    x1 = min(current["bboxX"], next_box["bboxX"])
    y1 = min(current["bboxY"], next_box["bboxY"])
    x2 = max(current["bboxX"] + current["bboxWidth"], next_box["bboxX"] + next_box["bboxWidth"])
    y2 = max(current["bboxY"] + current["bboxHeight"], next_box["bboxY"] + next_box["bboxHeight"])
    return {"bboxX": x1, "bboxY": y1, "bboxWidth": x2 - x1, "bboxHeight": y2 - y1}


def same_subtitle_key(left: str, right: str) -> bool:
    if left == right:
        return True
    shorter, longer = sorted((left, right), key=len)
    return len(shorter) >= 3 and shorter in longer


def normalize_for_group(text: str) -> str:
    return "".join(ch.lower() for ch in normalize_text(text or "") if ch.isalnum())


def to_segment(item: dict) -> dict:
    start = float(item["start"])
    end = max(float(item["end"]), start + 0.2)
    text = item["text"]
    bbox = item.get("bbox") or {}
    return {
        "start": start,
        "end": end,
        "text": text,
        "words": infer_words(text, start, end),
        "source": "VIDEO_SUBTITLE",
        "bboxX": bbox.get("bboxX"),
        "bboxY": bbox.get("bboxY"),
        "bboxWidth": bbox.get("bboxWidth"),
        "bboxHeight": bbox.get("bboxHeight"),
    }


def infer_words(text: str, start: float, end: float) -> list[dict]:
    tokens = tokenize_text(text)
    if not tokens:
        return []
    duration = max(0.01, end - start)
    step = duration / len(tokens)
    words = []
    for index, token in enumerate(tokens):
        word_start = start + step * index
        word_end = end if index == len(tokens) - 1 else start + step * (index + 1)
        words.append({"word": token, "start": word_start, "end": word_end})
    return words


def tokenize_text(text: str) -> list[str]:
    tokens = []
    i = 0
    while i < len(text):
        ch = text[i]
        if not ch.isalnum():
            i += 1
            continue
        if is_cjk(ch):
            tokens.append(ch)
            i += 1
            continue
        start = i
        while i < len(text) and text[i].isalnum() and not is_cjk(text[i]):
            i += 1
        tokens.append(text[start:i])
    return tokens


def is_cjk(ch: str) -> bool:
    return (
        "\u4e00" <= ch <= "\u9fff"
        or "\u3040" <= ch <= "\u30ff"
        or "\uac00" <= ch <= "\ud7af"
    )


if __name__ == "__main__":
    import uvicorn

    # 直接 python app.py 启动时使用,默认端口需与后端 app.asr.base-url 一致(9000)。
    # host/port 可用 ASR_HOST / ASR_PORT 覆盖。
    host = os.getenv("ASR_HOST", "127.0.0.1")
    port = int(os.getenv("ASR_PORT", "9000"))
    uvicorn.run(app, host=host, port=port)
