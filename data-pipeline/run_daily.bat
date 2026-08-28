@echo off
REM Daily stock data update. Double-click to run.
REM Build stock.db (AKShare from China) then push to cdn branch -> Render auto-deploys -> phone pulls.
REM If your Clash proxy port changed, edit PROXY_ADDR below.
chcp 65001 >nul
set PROXY_ADDR=127.0.0.1:52850
echo Running daily data update (build + push cdn). See data-pipeline\run_daily.log ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_daily.ps1"
echo.
echo Finished. Logs: data-pipeline\run_daily.log
pause
