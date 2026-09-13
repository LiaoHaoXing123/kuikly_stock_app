@echo off
chcp 65001 >nul
set PROXY_ADDR=127.0.0.1:7899
set GITEE_REPO=https://gitee.com/LiaoHaoXing123/kuikly-stock-data.git
set GITEE_BRANCH=master
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_daily.ps1"
