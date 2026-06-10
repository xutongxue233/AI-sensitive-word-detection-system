@echo off
rem Dev launcher: run all four services in this single window. See scripts\local\dev.ps1
chcp 65001 >nul
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local\dev.ps1" %*
if errorlevel 1 pause
endlocal
