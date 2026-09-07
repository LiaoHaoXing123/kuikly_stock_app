@echo off
REM Scheduled-task entry for daily data update (no pause).
chcp 65001 >nul
set PROXY_ADDR=127.0.0.1:7899
REM Gitee 公开数据仓库（手机从 Gitee 下载）；计划任务也推 Gitee
set GITEE_REPO=https://gitee.com/LiaoHaoXing123/kuikly-stock-data.git
set GITEE_BRANCH=master
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_daily.ps1"
