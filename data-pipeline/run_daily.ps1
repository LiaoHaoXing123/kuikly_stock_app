# run_daily.ps1 - 每日数据更新（国内取数，稳定）
# 1) 本机 venv 跑 build_stock_db.py -> stock.db + version.json + stock.sql
# 2) 推 stock.db/version.json/stock.sql 到 cdn 分支(github) -> Render 自动部署 -> 手机拉取
# 3) 可选：推送到 Gitee（设 $env:GITEE_REPO，如 https://gitee.com/<user>/<repo>.git，且 GITEE_BRANCH 默认 master）
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoDir   = Split-Path -Parent $MyInvocation.MyCommand.Path     # = data-pipeline
$repoRoot  = Split-Path -Parent $repoDir                        # = 仓库根
$logPath   = Join-Path $repoDir "run_daily.log"
$buildLog  = Join-Path $repoDir "build_stock_db.log"
$gitUrl    = "https://github.com/LiaoHaoXing123/kuikly_stock_app.git"
$giteeRepo = $env:GITEE_REPO
$giteeBranch = if ($env:GITEE_BRANCH) { $env:GITEE_BRANCH } else { "master" }
$proxy     = $env:PROXY_ADDR
$files     = @("stock.db", "version.json", "stock.sql")

function Log($m) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m
    Add-Content -Path $logPath -Value $line -Encoding UTF8
    Write-Host $line
}

function Push-ToRepo($originUrl, $branchName) {
    # 隔离临时目录推送，绝不触碰仓库工作区；temp 用 GetTempPath 且校验不落在仓库内
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cdn_" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    if ($tmp.TrimEnd("\").Length -le $repoRoot.TrimEnd("\").Length -and $tmp.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "临时目录落在仓库内，已中止推送: $tmp"
    }
    git -C $tmp init 2>&1 | Out-Null
    git -C $tmp remote add origin $originUrl 2>&1 | Out-Null
    git -C $tmp fetch origin $branchName 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { git -C $tmp checkout -B $branchName 2>&1 | Out-Null }
    else { git -C $tmp checkout --orphan $branchName 2>&1 | Out-Null }
    foreach ($f in $files) {
        if (Test-Path (Join-Path $repoDir $f)) { Copy-Item (Join-Path $repoDir $f) -Destination $tmp -Force }
    }
    git -C $tmp add -f $files 2>&1 | Out-Null
    git -C $tmp -c user.name="kuikly-stock" -c user.email="bot@example.com" commit -m ("data update " + (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")) 2>&1 | Out-Null
    git -C $tmp push --force origin $branchName 2>&1 | ForEach-Object { Log ("   " + $_) }
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

Log "=== 每日数据更新开始 ==="

# ---- 1) 构建 stock.db + version.json + stock.sql ----
Log "[1/3] 构建 stock.db / version.json / stock.sql ..."
$py = Join-Path $repoDir "venv\Scripts\python.exe"
if (-not (Test-Path $py)) { $py = "python" }
$env:TQDM_DISABLE = "1"
$buildOut = & $py (Join-Path $repoDir "build_stock_db.py") 2>&1 | Out-String
Add-Content -Path $buildLog -Value $buildOut -Encoding UTF8
if ($LASTEXITCODE -ne 0) {
    Log "!! build_stock_db.py 失败 (exit $LASTEXITCODE)，请查看 build_stock_db.log";
    Exit 1
}
($buildOut -split "`n") | Where-Object { $_ -match "完成|覆盖|SQL|✓|警告|retry|Error|Traceback|行$" } | ForEach-Object { Log ("   " + $_.Trim()) }

# ---- 2) 推送到 github cdn 分支 -> Render ----
Log "[2/3] 推送到 github cdn 分支 ..."
if (-not $proxy) { $proxy = "127.0.0.1:52850"; Log "   注意：未设置 PROXY_ADDR，默认用 $proxy" }
$oldP = $env:HTTP_PROXY; $oldH = $env:HTTPS_PROXY
$env:HTTP_PROXY  = "http://" + $proxy
$env:HTTPS_PROXY = "http://" + $proxy
$githubPushed = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    try { Push-ToRepo $gitUrl "cdn"; $githubPushed = $true; break }
    catch { Log ("   第 $attempt 次 github 推送失败: " + $_.Exception.Message) }
}
if (-not $githubPushed) { Log "!! github cdn 推送失败（请确认 Clash github.com 节点可用；数据已留在 data-pipeline）" }

# ---- 3) 可选：Gitee ----
if ($giteeRepo) {
    Log "[3/3] 推送到 Gitee ($giteeBranch) ..."
    $giteePushed = $false
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try { Push-ToRepo $giteeRepo $giteeBranch; $giteePushed = $true; break }
        catch { Log ("   第 $attempt 次 Gitee 推送失败: " + $_.Exception.Message) }
    }
    if (-not $giteePushed) { Log "!! Gitee 推送失败" }
} else {
    Log "[3/3] 未配置 GITEE_REPO，跳过 Gitee 推送。"
}
$env:HTTP_PROXY = $oldP; $env:HTTPS_PROXY = $oldH

if (-not $githubPushed -and -not ($giteeRepo)) { Exit 1 }
Log "=== 完成。所有日志见 run_daily.log / build_stock_db.log ==="

