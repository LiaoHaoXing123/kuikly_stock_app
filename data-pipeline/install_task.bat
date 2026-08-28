@echo off
chcp 65001 >nul
REM One-click register daily scheduled task (16:35). Auto-elevates to admin.
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator rights...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

set "PSP=%~dp0run_daily.ps1"
echo Registering daily task "KuiklyStockDataUpdate" at 16:35 ...
schtasks /Create /TN "KuiklyStockDataUpdate" /TR "powershell -NoProfile -ExecutionPolicy Bypass -File %PSP%" /SC DAILY /ST 16:35 /F
echo.
echo Done. Task details:
schtasks /Query /TN "KuiklyStockDataUpdate"
echo.
pause
