@echo off
REM Daily stock data update. Double-click to run.
REM Build stock.db (AKShare from China) then push to cdn branch -> Render auto-deploys -> phone pulls.
REM If your Clash proxy port changed, edit PROXY_ADDR below.
chcp 65001 >nul
set PROXY_ADDR=127.0.0.1:52850
REM Gitee 公开数据仓库（手机从 Gitee 下载）；改端口/仓库时编辑这两行
set GITEE_REPO=https://gitee.com/LiaoHaoXing123/kuikly-stock-data.git
set GITEE_BRANCH=master
echo Running daily data update (build + push cdn). See data-pipeline\run_daily.log ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_daily.ps1"
echo.
echo Finished. Logs: data-pipeline\run_daily.log
pause
