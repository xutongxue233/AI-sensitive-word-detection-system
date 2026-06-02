import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, UploadFile
from faster_whisper import WhisperModel
from opencc import OpenCC


MODEL_SIZE = os.getenv("WHISPER_MODEL", "medium")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
INITIAL_PROMPT = os.getenv("WHISPER_INITIAL_PROMPT", "请使用简体中文转写普通话内容。")

app = FastAPI(title="Local Whisper ASR Service")
model: WhisperModel | None = None
converter = OpenCC(CHINESE_CONVERTER) if CHINESE_CONVERTER else None


def normalize_text(text: str) -> str:
    value = text.strip()
    return converter.convert(value) if converter else value


def get_model() -> WhisperModel:
    global model
    if model is None:
        model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
    return model


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...), word_timestamps: bool = True):
    suffix = Path(file.filename or "audio.wav").suffix or ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        temp_file.write(await file.read())
        temp_path = temp_file.name
    try:
        segments, _ = get_model().transcribe(
            temp_path,
            vad_filter=True,
            word_timestamps=word_timestamps,
            initial_prompt=INITIAL_PROMPT,
            language=os.getenv("WHISPER_LANGUAGE") or None,
        )
        output = []
        for segment in segments:
            words = []
            for word in segment.words or []:
                words.append(
                    {
                        "word": normalize_text(word.word),
                        "start": float(word.start or segment.start),
                        "end": float(word.end or segment.end),
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
