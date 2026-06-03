@echo off
rem ============================================================
rem  AI sensitive-word detection system - one-click launcher
rem  Opens ASR / Backend / Frontend in three separate windows.
rem  Prerequisite: MySQL is running (root/root), FFmpeg ready.
rem ============================================================

echo.
echo [START] AI sensitive-word detection system
echo   Backend : http://localhost:8090
echo   Frontend: http://127.0.0.1:5174
echo   ASR     : http://127.0.0.1:9000
echo.
echo Make sure MySQL is running before backend starts.
echo.

start "ASR-Whisper-9000" cmd /k "%~dp0_run-asr.bat"
start "Backend-8090"     cmd /k "%~dp0_run-backend.bat"
start "Frontend-5174"    cmd /k "%~dp0_run-frontend.bat"

echo Three service windows have been launched.
echo First ASR run will download the whisper model, please wait.
echo When all are ready, open: http://127.0.0.1:5174/
echo.
pause
