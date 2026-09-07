@echo off
chcp 65001 >nul
REM One-click register daily scheduled task (16:35). Auto-elevates to admin.
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator rights...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

set "TASKBAT=%~dp0run_daily_task.bat"
echo Registering daily task "KuiklyStockDataUpdate" at 16:35 ...
schtasks /Create /TN "KuiklyStockDataUpdate" /TR "\"%TASKBAT%\"" /SC DAILY /ST 16:35 /F
echo.
echo Done. Task details:
schtasks /Query /TN "KuiklyStockDataUpdate"
echo.
pause
