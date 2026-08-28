# run_daily.ps1 - 每日数据更新（国内取数，稳定）
# 1) 用本机 venv 跑 build_stock_db.py（AKShare 取数 -> stock.db + version.json）
# 2) 把 stock.db + version.json 推到 cdn 分支 -> Render 自动部署 -> 手机端自动/手动刷新拉到
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoDir   = Split-Path -Parent $MyInvocation.MyCommand.Path     # = data-pipeline
$repoRoot  = Split-Path -Parent $repoDir                        # = 仓库根
$logPath   = Join-Path $repoDir "run_daily.log"
$buildLog  = Join-Path $repoDir "build_stock_db.log"
$gitUrl    = "https://github.com/LiaoHaoXing123/kuikly_stock_app.git"
$proxy     = $env:PROXY_ADDR

function Log($m) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m
    Add-Content -Path $logPath -Value $line -Encoding UTF8
    Write-Host $line
}

Log "=== 每日数据更新开始 ==="

# ---- 1) 构建 stock.db ----------------
Log "[1/2] 构建 stock.db (AKShare 取数) ..."
$py = Join-Path $repoDir "venv\Scripts\python.exe"
if (-not (Test-Path $py)) { $py = "python" }
# 关掉 akshare 进度条，避免刷屏
$env:TQDM_DISABLE = "1"
$buildOut = & $py (Join-Path $repoDir "build_stock_db.py") 2>&1 | Out-String
Add-Content -Path $buildLog -Value $buildOut -Encoding UTF8
if ($LASTEXITCODE -ne 0) {
    Log "!! build_stock_db.py 失败 (exit $LASTEXITCODE)，请查看 build_stock_db.log";
    Exit 1
}
($buildOut -split "`n") | Where-Object { $_ -match "覆盖|完成|✓|警告|retry|Error|Traceback|行$" } | ForEach-Object { Log ("   " + $_.Trim()) }
Log "[1/2] 构建完成 -> stock.db / version.json"

# ---- 2) 推送到 cdn 分支（隔离临时目录，不污染主工作区）----
Log "[2/2] 推送到 cdn 分支 ..."
if (-not $proxy) { $proxy = "127.0.0.1:52850"; Log "   注意：未设置 PROXY_ADDR，默认用 $proxy" }
$env:HTTP_PROXY  = "http://" + $proxy
$env:HTTPS_PROXY = "http://" + $proxy

$pushed = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    $tmp = Join-Path $env:TEMP ("cdn_" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        git -C $tmp init 2>&1 | Out-Null
        git -C $tmp remote add origin $gitUrl 2>&1 | Out-Null
        git -C $tmp fetch origin cdn 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { git -C $tmp checkout -B cdn 2>&1 | Out-Null; git -C $tmp reset --hard origin/cdn 2>&1 | Out-Null }
        else { git -C $tmp checkout --orphan cdn 2>&1 | Out-Null }
        Copy-Item (Join-Path $repoDir "stock.db") -Destination $tmp -Force
        Copy-Item (Join-Path $repoDir "version.json") -Destination $tmp -Force
        git -C $tmp add -f stock.db version.json 2>&1 | Out-Null
        git -C $tmp -c user.name="kuikly-stock" -c user.email="bot@example.com" commit -m ("data update " + (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")) 2>&1 | Out-Null
        git -C $tmp push --force origin cdn 2>&1 | ForEach-Object { Log ("   " + $_) }
        if ($LASTEXITCODE -eq 0) { $pushed = $true; break }
        else { Log ("   第 $attempt 次推送失败，重试...") }
    } catch {
        Log ("   第 $attempt 次推送异常: " + $_.Exception.Message)
    } finally {
        if ($tmp -and (Test-Path $tmp)) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
    }
}

if (-not $pushed) {
    Log "!! 推送 cdn 失败：无法连接 github.com（请确认 Clash 代理 github.com 节点可用 / 未把 github.com 直连导致被墙）。"
    Log "   stock.db 与 version.json 已在 data-pipeline 生成，可稍后重跑 run_daily.bat 或手动推到 cdn 分支。"
    Exit 1
}
Log "[2/2] 已推送到 cdn 分支，Render 将自动部署到手机端。"
Log "=== 完成。所有日志见 run_daily.log / build_stock_db.log ==="

