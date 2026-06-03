@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  Stop services by port: 9000 (ASR) / 8090 (Backend) / 5174 (Frontend)
rem ============================================================

echo.
echo [STOP] Terminating ASR(9000) / Backend(8090) / Frontend(5174) ...
echo.

for %%P in (9000 8090 5174) do (
    set "FOUND="
    for /f "tokens=5" %%I in ('netstat -ano ^| findstr ":%%P " ^| findstr LISTENING') do (
        set "FOUND=1"
        echo   port %%P -^> PID %%I, killing...
        taskkill /F /PID %%I >nul 2>&1
    )
    if not defined FOUND echo   port %%P not listening, skip.
)

echo.
echo [DONE] services stopped.
echo.
pause
