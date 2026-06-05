@echo off
setlocal
rem ============================================================
rem  asr-service (Whisper) GPU setup - RTX 50 series / Blackwell sm_120
rem  Installs CUDA 13.0 PyTorch (includes sm_120 kernels).
rem  NOTE: OCR runs in a SEPARATE venv (.venv-ocr) - see install-ocr-gpu.bat.
rem        torch(CUDA13) and paddlepaddle-gpu(CUDA12) cuDNN cannot coexist
rem        in one process, so Whisper and OCR are split into two venvs.
rem ============================================================
set PY=%~dp0.venv\Scripts\python.exe
if not exist "%PY%" (
  echo [ERROR] venv not found: %PY%
  echo         Create asr-service\.venv first, then: pip install -r requirements.txt
  exit /b 1
)

echo [1/2] Uninstall CPU torch, install torch 2.12.0 cu130 (CUDA 13.0, sm_120) ...
"%PY%" -m pip uninstall -y torch torchvision torchaudio
"%PY%" -m pip install torch==2.12.0 --index-url https://download.pytorch.org/whl/cu130
if errorlevel 1 ( echo [ERROR] torch install failed & exit /b 1 )

echo [2/2] GPU verify ...
"%PY%" -c "import torch;print('torch', torch.__version__, 'cuda', torch.cuda.is_available()); print('arch', torch.cuda.get_arch_list() if torch.cuda.is_available() else 'N/A')"

echo.
echo [DONE] If torch cuda=True with sm_120 in arch, Whisper GPU is ready.
echo Next: set WHISPER_DEVICE=cuda / WHISPER_FP16=true, then run: .\.venv\Scripts\python.exe app.py
echo For OCR GPU, run install-ocr-gpu.bat (creates a separate .venv-ocr).
endlocal
