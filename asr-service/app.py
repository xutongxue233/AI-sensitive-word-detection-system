import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, UploadFile
from opencc import OpenCC
import whisper


MODEL_SIZE = os.getenv("WHISPER_MODEL", "large-v3")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
FP16 = os.getenv("WHISPER_FP16", "false").lower() in {"1", "true", "yes", "on"}
CHINESE_CONVERTER = os.getenv("WHISPER_CHINESE_CONVERTER", "t2s")
INITIAL_PROMPT = os.getenv("WHISPER_INITIAL_PROMPT", "请使用简体中文转写普通话内容。")
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


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model": MODEL_SIZE,
        "device": DEVICE,
        "fp16": FP16,
        "modelLoaded": model is not None,
    }


def normalize_text(text: str) -> str:
    value = text.strip()
    return converter.convert(value) if converter else value


def get_model():
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
            task="transcribe",
            initial_prompt=INITIAL_PROMPT,
            language=os.getenv("WHISPER_LANGUAGE") or None,
            word_timestamps=word_timestamps,
            fp16=FP16,
            verbose=False,
        )
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
