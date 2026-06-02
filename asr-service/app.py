import os
import tempfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, UploadFile
from opencc import OpenCC
import torch
import whisper


MODEL_SIZE = os.getenv("WHISPER_MODEL", "large-v3")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
INITIAL_PROMPT = os.getenv("WHISPER_INITIAL_PROMPT", "请使用简体中文转写普通话内容。")
NO_SPEECH_THRESHOLD = float(os.getenv("WHISPER_NO_SPEECH_THRESHOLD", "0.6"))
MIN_AVG_LOGPROB = float(os.getenv("WHISPER_MIN_AVG_LOGPROB", "-1.0"))
MAX_COMPRESSION_RATIO = float(os.getenv("WHISPER_MAX_COMPRESSION_RATIO", "2.4"))
DEFAULT_FFMPEG_BIN = Path(__file__).resolve().parents[1] / "backend" / "tools" / "ffmpeg" / "bin"
if DEFAULT_FFMPEG_BIN.exists():
    os.environ["PATH"] = f"{DEFAULT_FFMPEG_BIN}{os.pathsep}{os.environ.get('PATH', '')}"

app = FastAPI(title="Local Whisper ASR Service")
model: Any | None = None
converter = OpenCC(CHINESE_CONVERTER) if CHINESE_CONVERTER else None


def normalize_text(text: str) -> str:
    value = text.strip()
    return converter.convert(value) if converter else value


def should_skip_segment(segment: dict[str, Any]) -> bool:
    text = normalize_text(segment.get("text") or "")
    if not text:
        return True

    no_speech_prob = segment.get("no_speech_prob")
    if no_speech_prob is not None and float(no_speech_prob) >= NO_SPEECH_THRESHOLD:
        return True

    avg_logprob = segment.get("avg_logprob")
    if avg_logprob is not None and float(avg_logprob) <= MIN_AVG_LOGPROB:
        return True

    compression_ratio = segment.get("compression_ratio")
    if compression_ratio is not None and float(compression_ratio) > MAX_COMPRESSION_RATIO:
        return True

    return False


def get_model() -> Any:
    global model
    if model is None:
        model = whisper.load_model(MODEL_SIZE, device=DEVICE)
    return model


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), word_timestamps: bool = True):
    suffix = Path(file.filename or "audio.wav").suffix or ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        temp_file.write(await file.read())
        temp_path = temp_file.name
    try:
        result = get_model().transcribe(
            temp_path,
            fp16=DEVICE == "cuda" and torch.cuda.is_available(),
            word_timestamps=word_timestamps,
            initial_prompt=INITIAL_PROMPT,
            language=os.getenv("WHISPER_LANGUAGE") or None,
            temperature=0,
            condition_on_previous_text=False,
            compression_ratio_threshold=MAX_COMPRESSION_RATIO,
            logprob_threshold=MIN_AVG_LOGPROB,
            no_speech_threshold=NO_SPEECH_THRESHOLD,
            hallucination_silence_threshold=1.0 if word_timestamps else None,
            verbose=False,
        )
        output = []
        for segment in result.get("segments", []):
            if should_skip_segment(segment):
                continue
            segment_start = float(segment.get("start") or 0)
            segment_end = float(segment.get("end") or segment_start)
            words = []
            for word in segment.get("words") or []:
                word_text = word.get("word") or ""
                words.append(
                    {
                        "word": normalize_text(word_text),
                        "start": float(word.get("start") or segment_start),
                        "end": float(word.get("end") or segment_end),
                    }
                )
            output.append(
                {
                    "start": segment_start,
                    "end": segment_end,
                    "text": normalize_text(segment.get("text") or ""),
                    "words": words,
                }
            )
        return {"segments": output}
    finally:
        Path(temp_path).unlink(missing_ok=True)
