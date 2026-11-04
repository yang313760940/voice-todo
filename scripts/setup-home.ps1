# ============================================================
#  setup-home.ps1  —— 家里电脑一键准备脚本（开发小白版）
#  作用：1) 从 GitHub 克隆项目（如果还没有）
#        2) 下载两个大文件（AAR + 离线模型）并按文档校验
#        3) 生成 local.properties（自动找 Android SDK）
#        4) 建 ASCII 路径联接，规避中文路径构建坑
#  用法：右键本文件 -> "使用 PowerShell 运行"；或在 PowerShell 里：
#        powershell -ExecutionPolicy Bypass -File setup-home.ps1
# ============================================================

$ErrorActionPreference = 'Stop'

# ------- 可修改的配置（一般不用动）-------
# 项目要放在哪个目录下（克隆到 $TARGET\语音提醒软件开发）。默认 E 盘，改成你的盘符：
$TARGET    = 'E:\AI工程'
$REPO_URL  = 'https://github.com/yang313760940/voice-todo.git'
$REPO_NAME = '语音提醒软件开发'

# ------- 两个大文件（来源、大小、哈希，均取自 docs/offline-toolchain.md）-------
$FILES = @(
  @{
    Name   = 'sherpa-onnx-1.13.8.aar'
    Url    = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar'
    RelDir = 'app\libs'
    Sha256 = ''   # 文档未给哈希；改用 ZIP 完整性校验（见 Verify-Aar）
    SizeMB = 47.81
  },
  @{
    Name   = 'model.int8.onnx'
    Url    = 'https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx'
    RelDir = 'app\src\main\assets\sherpa'
    Sha256 = '3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC'
    SizeMB = 78.04
  },
  @{
    Name   = 'tokens.txt'
    Url    = 'https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/tokens.txt'
    RelDir = 'app\src\main\assets\sherpa'
    Sha256 = ''
    SizeMB = 0.07
  }
)

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($m)   { Write-Host "  [!] $m" -ForegroundColor Yellow }

# ---------- 0. 检查基础工具 ----------
Write-Step '0/5 检查 Git'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  Write-Host '没有找到 Git。请先安装：https://git-scm.com/download/win （装完重开窗口再跑本脚本）' -ForegroundColor Red
  Read-Host '按回车退出'; exit 1
}
Write-Ok 'Git 已就绪'

# ---------- 1. 克隆或更新仓库 ----------
Write-Step '1/5 拉取项目代码'
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

# ---------- 2. 下载大文件 + 校验 ----------
Write-Step '2/5 下载并校验大文件（体积大，可能耗时几分钟）'
foreach ($f in $FILES) {
  $destDir = Join-Path $repoPath $f.RelDir
  if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
  $dest = Join-Path $destDir $f.Name

  if (Test-Path $dest) {
    Write-Ok "已存在，跳过下载：$($f.Name)"
  } else {
    Write-Host "  下载 $($f.Name) (~$($f.SizeMB) MB)..."
    # curl 比 Invoke-WebRequest 对镜像/GitHub 更稳；--ssl-no-revoke 规避吊销检查报错（文档第80行）
    curl.exe -L --ssl-no-revoke --retry 3 --retry-delay 2 -o $dest $f.Url
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $dest)) {
      Write-Host "  下载失败：$($f.Name)。可手动用浏览器打开下列地址下载后放到 $destDir" -ForegroundColor Red
      Write-Host "    $($f.Url)" -ForegroundColor Red
      continue
    }
  }

  # 校验
  if ($f.Sha256) {
    $actual = (Get-FileHash -Algorithm SHA256 -Path $dest).Hash
    if ($actual -eq $f.Sha256) { Write-Ok "$($f.Name) SHA-256 校验通过" }
    else { Write-Host "  $($f.Name) SHA-256 不匹配！期望 $($f.Sha256) 实际 $actual，请删除重下。" -ForegroundColor Red }
  } elseif ($f.Name -like '*.aar') {
    # AAR 用 ZIP 完整性校验（文档要求：含有效 EOCD，4 个 ABI）
    try {
      $bytes = [System.IO.File]::ReadAllBytes($dest)
      $hasEocd = $false
      $tail = [Math]::Min(66000, $bytes.Length)
      for ($i = $bytes.Length - 22; $i -ge $bytes.Length - $tail -and $i -ge 0; $i--) {
        if ($bytes[$i] -eq 0x50 -and $bytes[$i+1] -eq 0x4B -and $bytes[$i+2] -eq 0x05 -and $bytes[$i+3] -eq 0x06) { $hasEocd = $true; break }
      }
      if ($hasEocd) { Write-Ok "$($f.Name) ZIP 结构完整（含 EOCD）" }
      else { Write-Host "  $($f.Name) 不是有效 ZIP/AAR，可能下载损坏，请删除重下。" -ForegroundColor Red }
    } catch { Write-Warn "AAR 结构校验跳过的异常：$_" }
  }
}

# ---------- 3. 生成 local.properties ----------
Write-Step '3/5 配置 Android SDK 路径'
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

# ---------- 4. 建 ASCII 联接（规避中文路径构建坑，文档第18-21行）----------
Write-Step '4/5 建 ASCII 路径联接（C:\voicetodo -> 项目）'
$link = 'C:\voicetodo'
try {
  if (Test-Path $link) { Write-Ok "联接已存在：$link" }
  else {
    cmd /c mklink /J "$link" "$repoPath" | Out-Null
    if (Test-Path $link) { Write-Ok "已建立：$link -> $repoPath" }
    else { Write-Warn "建联接失败（可能需要管理员）。不建也能编译，但中文路径下测试可能报错。" }
  }
} catch { Write-Warn "建联接异常：$_" }

# ---------- 5. 完成 ----------
Write-Step '5/5 全部就绪'
Write-Host @"

下一步：
  - 用 Android Studio 打开项目：$repoPath
  - 或命令行构建（推荐走 ASCII 联接路径）：
        cd C:\voicetodo
        .\gradlew.bat :app:assembleDebug

如果哪个文件下载失败，按屏幕上红色提示的地址用浏览器手动下载，
放到对应目录后重新运行本脚本即可（已存在的会自动跳过）。

"@ -ForegroundColor White
Read-Host '按回车关闭窗口'
