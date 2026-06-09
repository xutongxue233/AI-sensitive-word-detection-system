@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local\start.ps1" %*
if errorlevel 1 pause
endlocal
