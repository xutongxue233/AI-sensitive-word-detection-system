@echo off
setlocal
rem ============================================================
rem  ocr-service (PaddleOCR) GPU setup - dedicated venv (.venv)
rem  Installs paddlepaddle-gpu (CUDA 12.9, sm_120) + paddleocr, WITHOUT torch.
rem  Why separate from Whisper: torch(CUDA13) and paddlepaddle-gpu(CUDA12)
rem  share the same cuDNN dll name and cannot coexist in one process
rem  (WinError 127). paddleocr pulls torch only if present; a torch-free
rem  venv makes it fall back to pure paddle, so OCR runs on GPU cleanly.
rem ============================================================
set OCRVENV=%~dp0.venv
echo [1/4] Create OCR venv at %OCRVENV% (Python 3.10) ...
py -3.10 -m venv "%OCRVENV%"
if errorlevel 1 ( echo [ERROR] venv creation failed; ensure Python 3.10 is installed (py -3.10) & exit /b 1 )
set PY=%OCRVENV%\Scripts\python.exe
"%PY%" -m pip install -U pip

echo [2/4] Install paddlepaddle-gpu 3.2.1 (CUDA 12.9, sm_120) ...
"%PY%" -m pip install paddlepaddle-gpu==3.2.1 -i https://www.paddlepaddle.org.cn/packages/stable/cu129/
if errorlevel 1 ( echo [ERROR] paddlepaddle-gpu install failed & exit /b 1 )

echo [3/4] Install nvidia-cuda-nvrtc (cuDNN runtime-compiled dependency) + paddleocr + service deps ...
echo        (this must NOT pull torch; a clean venv keeps paddleocr on pure paddle)
"%PY%" -m pip install nvidia-cuda-nvrtc-cu12 paddleocr fastapi "uvicorn[standard]" python-multipart opencc-python-reimplemented opencv-python-headless "numpy==1.26.4"
if errorlevel 1 ( echo [ERROR] paddleocr / deps install failed & exit /b 1 )

echo [4/4] Verify ...
"%PY%" -c "import paddle; paddle.utils.run_check()"
"%PY%" -m pip show torch >nul 2>&1 && echo [WARN] torch present in OCR venv - it must NOT be here (would conflict with paddle cuDNN, causing WinError 127)

echo.
echo [DONE] If paddle run_check passes on GPU and the WARN above did NOT appear, OCR GPU is ready.
echo Next: set PADDLE_OCR_USE_GPU=true, then run: .\.venv\Scripts\python.exe ocr_app.py  (port 9001)
endlocal
