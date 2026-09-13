# 本地构建行情，并通过独立临时仓库发布快照。
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $repoDir
$logPath   = Join-Path $repoDir "run_daily.log"
$buildLog  = Join-Path $repoDir "build_stock_db.log"
$gitUrl    = "https://github.com/LiaoHaoXing123/kuikly_stock_app.git"
$giteeRepo = $env:GITEE_REPO
$giteeBranch = if ($env:GITEE_BRANCH) { $env:GITEE_BRANCH } else { "master" }
$proxy     = $env:PROXY_ADDR
$files     = @("stock.db", "version.json")

function Log($m) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m
    Add-Content -Path $logPath -Value $line -Encoding UTF8
    Write-Host $line
}

function Push-ToRepo($originUrl, $branchName, [switch]$Direct) {

    $gitArgs = @()
    if ($Direct) { $gitArgs = @("-c", "http.proxy=", "-c", "https.proxy=") }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cdn_" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    if ($tmp.TrimEnd("\").Length -le $repoRoot.TrimEnd("\").Length -and $tmp.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "临时目录落在仓库内，已中止推送: $tmp"
    }
    git @gitArgs -C $tmp init 2>&1 | Out-Null
    git @gitArgs -C $tmp remote add origin $originUrl 2>&1 | Out-Null
    git @gitArgs -C $tmp fetch origin $branchName 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { git @gitArgs -C $tmp checkout -B $branchName 2>&1 | Out-Null }
    else { git @gitArgs -C $tmp checkout --orphan $branchName 2>&1 | Out-Null }
    foreach ($f in $files) {
        if (Test-Path (Join-Path $repoDir $f)) { Copy-Item (Join-Path $repoDir $f) -Destination $tmp -Force }
    }
    git @gitArgs -C $tmp add -f $files 2>&1 | Out-Null
    git @gitArgs -C $tmp -c user.name="kuikly-stock" -c user.email="bot@example.com" commit -m ("data update " + (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")) 2>&1 | Out-Null
    git @gitArgs -C $tmp push --force origin $branchName 2>&1 | ForEach-Object { Log ("   " + $_) }
    if ($LASTEXITCODE -ne 0) { throw "git push 失败(exit $LASTEXITCODE): $originUrl $branchName" }
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

Log "=== 每日数据更新开始 ==="

Log "[1/3] 构建 stock.db / version.json ..."
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

Log "[1b/3] 导出内置 JSON 资产（index/sector/fundflow）..."
$assetOut = & $py (Join-Path $repoDir "export_common_assets.py") 2>&1 | Out-String
Add-Content -Path $buildLog -Value $assetOut -Encoding UTF8
if ($LASTEXITCODE -ne 0) {
    Log "!! export_common_assets.py 失败 (exit $LASTEXITCODE)，JSON 资产可能停留在旧快照"
} else {
    ($assetOut -split "`n") | Where-Object { $_ -match "KB|写入|ERR" } | ForEach-Object { Log ("   " + $_.Trim()) }
}

Log "[2/3] 推送到 github cdn 分支 ..."
if (-not $proxy) { $proxy = "127.0.0.1:7899"; Log "   注意：未设置 PROXY_ADDR，默认用 $proxy" }
$oldP = $env:HTTP_PROXY; $oldH = $env:HTTPS_PROXY
$env:HTTP_PROXY  = "http://" + $proxy
$env:HTTPS_PROXY = "http://" + $proxy
$githubPushed = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    try { Push-ToRepo $gitUrl "cdn"; $githubPushed = $true; break }
        catch { Log ("   第 $attempt 次 github 推送失败: " + $_.Exception.Message); if ($attempt -lt 3) { Start-Sleep -Seconds 10 } }
}
$env:HTTP_PROXY = $oldP; $env:HTTPS_PROXY = $oldH
if (-not $githubPushed) { Log "!! github cdn 推送失败（请确认 Clash github.com 节点可用；数据已留在 data-pipeline）" }

if ($giteeRepo) {
    Log "[3/3] 推送到 Gitee ($giteeBranch, 直连) ..."
    $env:HTTP_PROXY = $null; $env:HTTPS_PROXY = $null; $env:ALL_PROXY = $null
    $giteePushed = $false
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try { Push-ToRepo $giteeRepo $giteeBranch -Direct; $giteePushed = $true; break }
        catch { Log ("   第 $attempt 次 Gitee 推送失败: " + $_.Exception.Message); if ($attempt -lt 3) { Start-Sleep -Seconds 10 } }
    }
    if (-not $giteePushed) { Log "!! Gitee 推送失败" }
} else {
    Log "[3/3] 未配置 GITEE_REPO，跳过 Gitee 推送。"
}

if (-not $githubPushed -and -not ($giteeRepo)) { Exit 1 }
Log "=== 完成。所有日志见 run_daily.log / build_stock_db.log ==="

