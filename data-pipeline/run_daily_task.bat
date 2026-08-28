@echo off
REM Scheduled-task entry for daily data update (no pause).
chcp 65001 >nul
set PROXY_ADDR=127.0.0.1:52850
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_daily.ps1"
