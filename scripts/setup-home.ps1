# ============================================================
#  setup-home.ps1  —— 家里电脑一键准备脚本（开发小白版）
#  作用：1) 从 GitHub 克隆项目（如果还没有）
#        2) 生成 local.properties（自动找 Android SDK）
#        3) 建 ASCII 路径联接，规避中文路径构建坑
#  用法：右键本文件 -> "使用 PowerShell 运行"；或在 PowerShell 里：
#        powershell -ExecutionPolicy Bypass -File setup-home.ps1
# ============================================================

$ErrorActionPreference = 'Stop'

# ------- 可修改的配置（一般不用动）-------
# 项目要放在哪个目录下（克隆到 $TARGET\语音提醒软件开发）。默认 E 盘，改成你的盘符：
$TARGET    = 'E:\AI工程'
$REPO_URL  = 'https://github.com/yang313760940/voice-todo.git'
$REPO_NAME = '语音提醒软件开发'

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($m)   { Write-Host "  [!] $m" -ForegroundColor Yellow }

# ---------- 0. 检查基础工具 ----------
Write-Step '0/4 检查 Git'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  Write-Host '没有找到 Git。请先安装：https://git-scm.com/download/win （装完重开窗口再跑本脚本）' -ForegroundColor Red
  Read-Host '按回车退出'; exit 1
}
Write-Ok 'Git 已就绪'

# ---------- 1. 克隆或更新仓库 ----------
Write-Step '1/4 拉取项目代码'
if (-not (Test-Path $TARGET)) { New-Item -ItemType Directory -Path $TARGET -Force | Out-Null }
$repoPath = Join-Path $TARGET $REPO_NAME

if (Test-Path (Join-Path $repoPath '.git')) {
  Write-Warn "目录已存在，执行 git pull 更新：$repoPath"
  git -C $repoPath pull
} else {
  git clone $REPO_URL $repoPath
}
if ($LASTEXITCODE -ne 0) { Write-Host '克隆/拉取失败，检查网络或登录。' -ForegroundColor Red; Read-Host '回车退出'; exit 1 }
Write-Ok "代码就绪：$repoPath"

# ---------- 2. 生成 local.properties ----------
Write-Step '2/4 配置 Android SDK 路径'
$lp = Join-Path $repoPath 'local.properties'
$sdkCandidates = @(
  "$env:LOCALAPPDATA\Android\Sdk",
  "$env:USERPROFILE\AppData\Local\Android\Sdk"
)
$sdk = $sdkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($sdk) {
  "sdk.dir=$($sdk -replace '\\','\\')" | Set-Content -Path $lp -Encoding ASCII
  Write-Ok "已写入 local.properties -> $sdk"
} else {
  Write-Warn "没自动找到 Android SDK。请装 Android Studio 并打开一次，然后手动在 $lp 里写：sdk.dir=C:\\你的\\Sdk\\路径"
}

# ---------- 3. 建 ASCII 联接（规避中文路径构建坑）----------
Write-Step '3/4 建 ASCII 路径联接（C:\voicetodo -> 项目）'
$link = 'C:\voicetodo'
try {
  if (Test-Path $link) { Write-Ok "联接已存在：$link" }
  else {
    cmd /c mklink /J "$link" "$repoPath" | Out-Null
    if (Test-Path $link) { Write-Ok "已建立：$link -> $repoPath" }
    else { Write-Warn "建联接失败（可能需要管理员）。不建也能编译，但中文路径下测试可能报错。" }
  }
} catch { Write-Warn "建联接异常：$_" }

# ---------- 4. 完成 ----------
Write-Step '4/4 全部就绪'
Write-Host @"

下一步：
  - 用 Android Studio 打开项目：$repoPath
  - 或命令行构建（推荐走 ASCII 联接路径）：
        cd C:\voicetodo
        .\gradlew.bat :app:assembleDebug

"@ -ForegroundColor White
Read-Host '按回车关闭窗口'
