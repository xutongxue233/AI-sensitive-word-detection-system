"""画面硬字幕 OCR 核心逻辑(RapidOCR / onnxruntime,纯 CPU 轻量版)。

由独立的 ocr_app.py 进程加载(默认端口 9001),跑在 ocr-service 专用 venv。引擎为
rapidocr-onnxruntime:ONNXRuntime CPU 推理 + wheel 自带的 PP-OCRv4 中英文检测/识别模型
(约十几 MB,随包分发、离线可用),不依赖 paddlepaddle/torch/CUDA,适配只有核显的机器,
识别质量与 PP-OCR 同源。

模块内 PADDLE_OCR_* 环境变量名与 probe_paddle_gpu 等公开名沿自早期 PaddleOCR 实现;
为保持 ocr_app.py 的 /health 字段与既有部署配置兼容,这些名称全部保留、语义对应适配。
"""

import os
import re
import threading
from pathlib import Path

from fastapi import HTTPException
from opencc import OpenCC


def env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.lower() in {"1", "true", "yes", "on"}


def env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default


PADDLE_OCR_LANG = os.getenv("PADDLE_OCR_LANG", "ch")
PADDLE_OCR_VERSION = os.getenv("PADDLE_OCR_VERSION", "PP-OCRv4")
# 可选:指定自定义 onnx 检测/识别模型路径(默认用 RapidOCR 自带的 PP-OCRv4 模型)。
PADDLE_OCR_DET_MODEL = os.getenv("PADDLE_OCR_DET_MODEL")
PADDLE_OCR_REC_MODEL = os.getenv("PADDLE_OCR_REC_MODEL")
# RapidOCR 走 onnxruntime CPU 推理,无 GPU 路径;保留变量仅为 /health 与既有配置兼容。
PADDLE_OCR_USE_GPU = env_bool("PADDLE_OCR_USE_GPU", False)
PADDLE_OCR_ENABLE_MKLDNN = env_bool("PADDLE_OCR_ENABLE_MKLDNN", False)
PADDLE_OCR_SHOW_STARTUP_LOGS = env_bool("PADDLE_OCR_SHOW_STARTUP_LOGS", False)
PADDLE_OCR_MIN_TEXT_LENGTH = max(1, env_int("PADDLE_OCR_MIN_TEXT_LENGTH", 2))
PADDLE_OCR_DROP_SHORT_LATIN = env_bool("PADDLE_OCR_DROP_SHORT_LATIN", True)
PADDLE_OCR_MIN_REPEAT_FRAMES = max(1, env_int("PADDLE_OCR_MIN_REPEAT_FRAMES", 2))
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
FFMPEG_BIN_DIR = os.getenv("FFMPEG_BIN_DIR")

if FFMPEG_BIN_DIR:
    os.environ["PATH"] = FFMPEG_BIN_DIR + os.pathsep + os.environ.get("PATH", "")
else:
    bundled_ffmpeg = Path(__file__).resolve().parents[1] / "backend" / "tools" / "ffmpeg" / "bin"
    if bundled_ffmpeg.exists():
        os.environ["PATH"] = str(bundled_ffmpeg) + os.pathsep + os.environ.get("PATH", "")

converter = OpenCC(CHINESE_CONVERTER) if CHINESE_CONVERTER else None
ocr_reader = None
ocr_reader_lang = None
# 初始化锁:保护全局 RapidOCR 引擎的懒加载(double-checked locking)。
_ocr_lock = threading.Lock()
# 推理锁:同一个全局 OCR 引擎不保证并发推理安全(多任务并行时),故串行化。
_ocr_infer_lock = threading.Lock()


def probe_paddle_gpu() -> dict:
    """返回 OCR 推理引擎自检信息,供 /health 暴露。

    函数名沿用 paddle 时代(ocr_app.py 引用),内容已改为 onnxruntime 探测;
    后端不解析此字段,仅供人工排查。任何探测异常都不抛出,记入 engineError。
    """
    info = {"engine": "rapidocr-onnxruntime"}
    try:
        import onnxruntime
        info["onnxruntimeVersion"] = onnxruntime.__version__
        info["providers"] = list(onnxruntime.get_available_providers())
    except Exception as exc:
        info["engineError"] = f"{type(exc).__name__}: {exc}"
    return info


def ocr_model_loaded() -> bool:
    return ocr_reader is not None


def run_ocr_on_video(video_path: str, interval_seconds: float, crop_bottom_ratio: float, min_confidence: float, lang: str):
    # 对外入口:加推理锁串行化,懒加载引擎后识别整段视频底部字幕。
    with _ocr_infer_lock:
        return recognize_video_subtitles(
            video_path,
            get_ocr_reader(lang),
            interval_seconds=max(0.2, interval_seconds),
            crop_bottom_ratio=min(1.0, max(0.05, crop_bottom_ratio)),
            min_confidence=min(1.0, max(0.0, min_confidence)),
        )


def normalize_text(text: str) -> str:
    value = text.strip()
    return converter.convert(value) if converter else value


def get_ocr_reader(lang: str):
    """按 cache_key 缓存并返回单例 RapidOCR 引擎(懒加载)。

    cache_key 由 语言 + OCR 版本 + det/rec 模型路径组合而成:任一变化都视为不同模型、需重建,
    否则复用已加载实例(初始化有成本)。RapidOCR 自带中英文模型、离线可用,lang 不再驱动模型
    下载、仅参与缓存键(后端调用本就不传 lang)。用双检锁(_ocr_lock)防止并发首次加载时重复
    初始化;依赖缺失或初始化失败抛 503。

    :param lang: OCR 语言;为空时回退到环境配置 PADDLE_OCR_LANG,再回退到 "ch"。
    :return: 可复用的 RapidOCR 引擎实例。
    """
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
                    from rapidocr_onnxruntime import RapidOCR
                except Exception as exc:
                    raise HTTPException(
                        status_code=503,
                        detail=f"RapidOCR 依赖未安装或不可用，请安装 ocr-service/requirements.txt: {exc}",
                    ) from exc
                kwargs = {}
                if PADDLE_OCR_DET_MODEL:
                    kwargs["det_model_path"] = PADDLE_OCR_DET_MODEL
                if PADDLE_OCR_REC_MODEL:
                    kwargs["rec_model_path"] = PADDLE_OCR_REC_MODEL
                try:
                    reader = RapidOCR(**kwargs)
                except Exception as exc:
                    raise HTTPException(
                        status_code=503,
                        detail=f"RapidOCR 引擎初始化失败: {exc}",
                    ) from exc
                ocr_reader = reader
                ocr_reader_lang = cache_key
    return ocr_reader


def recognize_video_subtitles(
    video_path: str,
    reader,
    interval_seconds: float,
    crop_bottom_ratio: float,
    min_confidence: float,
):
    """逐帧采样识别视频底部硬字幕,归并为带时间轴/坐标框的 segment 列表。

    工作流程:按 interval_seconds 间隔取帧 → 对每帧识别到的文字块,按文本相似度+位置关联到正在
    跟踪的"活跃字幕"(actives);同一条字幕连续多帧未再出现(超过 1.5 个采样间隔)即视为消失,
    收尾成一条 segment。最后统一做前后 padding(片头字幕收到 0、其余前后各外扩半个间隔),减少
    擦除时的"开头漏擦"与"前后各露出 0.几秒"闪烁。

    :param video_path: 本地视频文件路径。
    :param reader: 已加载的 OCR 引擎。
    :param interval_seconds: 采样间隔(秒)。
    :param crop_bottom_ratio: 只识别画面底部多大比例的区域(0~1)。
    :param min_confidence: 文本置信度过滤阈值。
    :return: 按起始时间排序的 segment 列表,每条含 start/end/text/words/source/bbox*。
    """
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
        actives = []  # 每块字幕独立跟踪: {key, text, bbox, start, end, last_seen, seen}
        t = 0.0
        while t <= duration + 0.001:
            cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
            ok, frame = cap.read()
            if not ok:
                break
            seg_end = min(duration, t + interval_seconds)
            blocks = read_subtitle_blocks(reader, frame, crop_bottom_ratio, min_confidence, cv2)
            matched = set()
            for block in blocks:
                key = normalize_for_group(block.get("text", ""))
                if not key:
                    continue
                found = None
                for idx, active in enumerate(actives):
                    if idx in matched:
                        continue
                    if same_subtitle_key(active["key"], key) and same_subtitle_box(active["bbox"], block):
                        found = idx
                        break
                if found is not None:
                    active = actives[found]
                    active["end"] = max(active["end"], seg_end)
                    active["last_seen"] = t
                    active["seen"] = int(active.get("seen", 1)) + 1
                    if len(block["text"]) > len(active["text"]):
                        active["text"] = block["text"]
                    active["bbox"] = merge_bbox(active["bbox"], block)
                    matched.add(found)
                else:
                    actives.append({"key": key, "text": block["text"], "bbox": block,
                                    "start": t, "end": max(seg_end, t + 0.2), "last_seen": t, "seen": 1})
                    matched.add(len(actives) - 1)
            # 连续多帧未再出现的块视为该字幕已消失,收尾成一条 segment
            survivors = []
            for active in actives:
                if t - active["last_seen"] > interval_seconds * 1.5:
                    append_active_segment(segments, active, interval_seconds)
                else:
                    survivors.append(active)
            actives = survivors
            t += interval_seconds
        for active in actives:
            append_active_segment(segments, active, interval_seconds)
        # 边界 padding:OCR 按 interval 采样,segment 起止最多差一个采样间隔;前后各外扩半个间隔、
        # 片头字幕(start < interval)直接收到 0,减少"开头漏擦"与"前后各露出 0.几秒"。
        pad = interval_seconds * 0.5
        for seg in segments:
            seg["start"] = 0.0 if seg["start"] < interval_seconds else max(0.0, seg["start"] - pad)
            seg["end"] = min(duration, seg["end"] + pad)
        segments.sort(key=lambda seg: seg["start"])
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


def read_subtitle_blocks(reader, frame, crop_bottom_ratio: float, min_confidence: float, cv2) -> list[dict]:
    # 返回该帧识别到的每个文字块(各自独立的归一化框),不再把整帧文字并成一块——
    # 这样每条字幕能单独定位坐标与时长,擦除只贴合命中的那一块,而非中间一大片。
    height, width = frame.shape[:2]
    crop_height = max(1, int(height * crop_bottom_ratio))
    crop_top = max(0, height - crop_height)
    crop = frame[crop_top:height, 0:width]
    if crop.size == 0:
        return []

    scale = min(2.0, max(1.0, 1280 / max(1, width)))
    if scale > 1.01:
        crop = cv2.resize(crop, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)

    try:
        raw_results = run_rapid_ocr(reader, crop)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"字幕 OCR 识别失败: {exc}") from exc

    blocks = []
    for bbox, text, confidence in raw_results:
        text = cleanup_ocr_text(text)
        if confidence >= min_confidence and text and bbox and not is_noise_text(text):
            box = union_bbox([bbox], width * scale, crop_height * scale, crop_top, scale, width, height)
            blocks.append({"text": text, **box})
    return blocks


def run_rapid_ocr(reader, crop) -> list[tuple[list, str, float]]:
    """执行一次 RapidOCR 推理,把结果归一为 (bbox 点列表, 文本, 置信度) 三元组列表。

    直接喂 BGR numpy 帧(无需落临时图片)。兼容两代返回形态:
    rapidocr_onnxruntime 1.x 返回 (result, elapse) 元组,result 为 [box, text, score] 列表;
    新版 rapidocr 返回带 boxes/txts/scores 属性的结果对象。统一适配,屏蔽版本差异。
    """
    output = reader(crop)
    if isinstance(output, tuple):
        output = output[0]
    if output is None:
        return []
    boxes = getattr(output, "boxes", None)
    if boxes is not None:
        txts = list(getattr(output, "txts", None) or [])
        scores = list(getattr(output, "scores", None) or [])
        items = []
        for index, text in enumerate(txts):
            box = boxes[index] if index < len(boxes) else None
            score = scores[index] if index < len(scores) else 1.0
            items.append((_to_point_list(box), str(text), float(score or 0)))
        return items
    items = []
    for line in output:
        if line is None or len(line) < 3:
            continue
        items.append((_to_point_list(line[0]), str(line[1]), float(line[2] or 0)))
    return items


def _to_point_list(box) -> list:
    # 规范化为点列表 [[x,y],...];兼容 numpy 数组、4 点多边形、[x1,y1,x2,y2] 矩形。
    if box is None:
        return []
    if hasattr(box, "tolist"):
        box = box.tolist()
    if not isinstance(box, (list, tuple)) or len(box) == 0:
        return []
    if isinstance(box[0], (list, tuple)):
        return [[float(p[0]), float(p[1])] for p in box if len(p) >= 2]
    if len(box) >= 4:
        x1, y1, x2, y2 = float(box[0]), float(box[1]), float(box[2]), float(box[3])
        return [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
    return []


def bbox_position(bbox) -> tuple[float, float]:
    points = bbox or []
    xs = [float(point[0]) for point in points if len(point) >= 2]
    ys = [float(point[1]) for point in points if len(point) >= 2]
    if not xs or not ys:
        return (0.0, 0.0)
    return (sum(ys) / len(ys), sum(xs) / len(xs))


def union_bbox(items: list, scaled_width: float, scaled_height: float, crop_top: int, scale: float, width: int, height: int) -> dict:
    """把裁剪放大坐标系下的点集还原回原图,并归一化为 0~1 的字幕框。

    OCR 是在"底部裁剪 + 放大"后的图上做的,坐标需换算回原视频帧:横坐标除以 scaled_width 归一;
    纵坐标先 除 scale 还原放大、再 加 crop_top 偏移补回裁掉的上半部分,最后除原图高度归一。取所有
    点的外接矩形作为并集框。无任何点时回退到底部默认框,避免后续擦除拿到空坐标。

    :param items: bbox 列表,每个 bbox 是点列表 [[x,y],...](裁剪放大坐标系)。
    :param scaled_width: 放大后裁剪图的宽,用于横向归一。
    :param scaled_height: 放大后裁剪图的高(当前保留以保持坐标语义完整)。
    :param crop_top: 底部裁剪在原图中的起始 y(被裁掉的上方高度),用于纵向偏移补回。
    :param scale: 裁剪图的放大倍率,纵坐标需先除以它还原。
    :param width: 原视频帧宽。
    :param height: 原视频帧高。
    :return: 归一化字幕框 {bboxX, bboxY, bboxWidth, bboxHeight}(均在 0~1)。
    """
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
    value = value.replace("　", " ")
    value = re.sub(r"\s+", " ", value).strip()
    return value


def significant_text(text: str) -> str:
    return "".join(ch for ch in text or "" if ch.isalnum())


def contains_cjk(text: str) -> bool:
    return any("一" <= ch <= "鿿" for ch in text or "")


def is_ascii_letters(value: str) -> bool:
    return bool(value) and all(("a" <= ch.lower() <= "z") for ch in value)


def is_noise_text(text: str) -> bool:
    compact = significant_text(text)
    if len(compact) < PADDLE_OCR_MIN_TEXT_LENGTH:
        return True
    if PADDLE_OCR_DROP_SHORT_LATIN and is_ascii_letters(compact) and len(compact) <= 3:
        return True
    return False


def same_subtitle_box(current: dict | None, block: dict) -> bool:
    # 判断跨帧两块是否同一条字幕:垂直中心接近(同一行高度)且水平有交叠。
    # 与文本相似度配合,把同一块字幕跨帧关联,避免与画面其它位置的文字混成一块。
    if not current or "bboxX" not in current or not block or "bboxX" not in block:
        return True
    cur_cy = current["bboxY"] + current["bboxHeight"] / 2
    blk_cy = block["bboxY"] + block["bboxHeight"] / 2
    if abs(cur_cy - blk_cy) > 0.08:
        return False
    cur_x2 = current["bboxX"] + current["bboxWidth"]
    blk_x2 = block["bboxX"] + block["bboxWidth"]
    overlap = min(cur_x2, blk_x2) - max(current["bboxX"], block["bboxX"])
    return overlap > -0.15


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


def append_active_segment(segments: list[dict], active: dict, interval_seconds: float):
    if should_keep_active(active, interval_seconds):
        segments.append(to_segment(active))


def should_keep_active(active: dict, interval_seconds: float) -> bool:
    text = active.get("text", "")
    if is_noise_text(text):
        return False
    seen = int(active.get("seen", 1))
    if seen >= PADDLE_OCR_MIN_REPEAT_FRAMES:
        return True
    compact = significant_text(text)
    if contains_cjk(compact):
        return True
    if len(compact) >= 6:
        return True
    duration = float(active.get("end", 0.0)) - float(active.get("start", 0.0))
    return duration >= interval_seconds * max(1, PADDLE_OCR_MIN_REPEAT_FRAMES)


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
    """为 OCR 文本伪造词级时间戳,以对齐 ASR 的 words 结构。

    OCR 只给出整条字幕的起止时间、没有词级时间戳,而下游(规则匹配/定位)按 ASR 的 word 时间戳
    工作。这里把字幕时长按 token 数等分,给每个 token 分配一段均匀 timing(最后一个 token 收到段尾),
    使两种来源的数据结构一致。这是近似 timing,仅用于把命中词大致定位到时间轴。

    :param text: 字幕文本。
    :param start: 字幕起始时间(秒)。
    :param end: 字幕结束时间(秒)。
    :return: [{word, start, end}, ...];文本无可用 token 时返回空列表。
    """
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
    """把文本切成 token:CJK 逐字成 token,连续拉丁/数字合并成词,跳过标点。

    中文等 CJK 字符按单字切分(符合"逐字定位"的需求);连续的拉丁字母/数字合并为一个词
    (如英文单词、价格数字),避免拆得过碎;非字母数字字符(标点/空格)直接跳过、不产生 token。
    供 infer_words 据 token 数等分时长。

    :param text: 待切分文本。
    :return: token 列表。
    """
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
        "一" <= ch <= "鿿"
        or "぀" <= ch <= "ヿ"
        or "가" <= ch <= "힯"
    )
