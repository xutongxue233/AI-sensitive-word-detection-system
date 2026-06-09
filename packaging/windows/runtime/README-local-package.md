# AI Sensitive Word Detection System - Local Package

This folder is a self-contained Windows runtime package.

## Start

Double-click:

```bat
start.bat
```

The browser opens:

```text
http://127.0.0.1:8090/
```

ASR, OCR, and backend run as hidden managed background processes. The launcher
does not open separate service windows; use `status.bat` and `logs\` for checks.

## Stop

Double-click:

```bat
stop.bat
```

## Status

Double-click:

```bat
status.bat
```

## Logs

Service logs are written to:

```text
logs\
```

## Configuration

The first start copies:

```text
config\local.env.example
```

to:

```text
config\local.env
```

Edit `config\local.env` to change ports, Whisper model, or CPU/GPU mode.

CPU mode is the default because it works on more machines. To use NVIDIA GPU,
edit:

```text
WHISPER_DEVICE=cuda
WHISPER_FP16=true
PADDLE_OCR_USE_GPU=true
```

The bundled Python virtual environments must also contain CUDA-compatible
torch/paddle wheels.

## Runtime Layout

```text
backend\video-moderation.jar  Spring Boot backend and embedded frontend
asr-service\                 Whisper ASR service
ocr-service\                 PaddleOCR subtitle OCR service
tools\ffmpeg\bin\            ffmpeg / ffprobe
storage\                     uploaded videos and exported files
logs\                        runtime logs
.runtime\                    pid files
```
