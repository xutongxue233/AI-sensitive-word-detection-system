@echo off
rem Stop all project services (backend/frontend/asr/ocr). See scripts\local\stop.ps1
chcp 65001 >nul
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local\stop.ps1" %*
if errorlevel 1 pause
endlocal
