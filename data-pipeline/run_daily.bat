@echo off
chcp 65001 >nul
set PROXY_ADDR=127.0.0.1:7899
set GITEE_REPO=https://gitee.com/LiaoHaoXing123/kuikly-stock-data.git
set GITEE_BRANCH=master
echo Running daily data update (build + push cdn). See data-pipeline\run_daily.log ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_daily.ps1"
echo.
echo Finished. Logs: data-pipeline\run_daily.log
pause
