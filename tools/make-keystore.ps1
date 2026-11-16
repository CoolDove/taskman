# 生成 TaskMan 的 release keystore —— 只需要跑一次。
#
# 用法（推荐：密码当场输入，不回显、也不进 shell 历史）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\make-keystore.ps1
#
# 也可以先给环境变量（适合自动化）：
#   $env:DOVE_KEYSTORE_PASS = '密码'; powershell -NoProfile -ExecutionPolicy Bypass -File tools\make-keystore.ps1
#
# 关键约定：
#   - keystore 放在用户目录（默认 ~\.android-keys\taskman-release.jks），永远不进仓库；
#     生成后路径写进用户环境变量 DOVE_ANDROID_KEYSTORE，build.ps1 / build.sh 自动读取。
#   - 同时写一份 ~\.android-keys\keystore.properties（含密码），构建脚本没拿到环境变量时读它。
#   - 密码文件必须和 keystore 一起备份到别处。keystore 丢了 = 已安装用户再也无法升级，
#     只能换包名重新发版。
param(
    [string]$Path = (Join-Path $env:USERPROFILE ".android-keys\taskman-release.jks"),
    [string]$Alias = "taskman",
    [string]$Dname = "CN=CoolDove, OU=Personal, O=CoolDove, C=CN",
    [int]$ValidityDays = 10000,
    [int]$KeySize = 4096,
    [switch]$Force
)
$ErrorActionPreference = "Stop"

if ((Test-Path $Path) -and -not $Force) {
    Write-Host "已存在：$Path" -ForegroundColor Yellow
    Write-Host "覆盖它会让已安装的用户无法升级，确认要重来请加 -Force。" -ForegroundColor Yellow
    exit 1
}

function Read-Plain([SecureString]$s) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$pass = $env:DOVE_KEYSTORE_PASS
if (-not $pass) {
    $p1 = Read-Plain (Read-Host "输入 keystore 密码（至少 6 位）" -AsSecureString)
    $p2 = Read-Plain (Read-Host "再输入一次确认" -AsSecureString)
    if ($p1 -ne $p2) { throw "两次输入不一致" }
    $pass = $p1
}
if ($pass.Length -lt 6) { throw "密码至少 6 位" }

$dir = Split-Path -Parent $Path
New-Item -ItemType Directory -Force -Path $dir | Out-Null

Write-Host "==> 生成 keystore：$Path"
# PKCS12 是现在 Android 工具链默认的容器格式；单个私钥 + 自签证书
& keytool -genkeypair -v -keystore $Path -storetype PKCS12 `
    -storepass $pass -keypass $pass -alias $Alias `
    -keyalg RSA -keysize $KeySize -sigalg SHA256withRSA -validity $ValidityDays `
    -dname $Dname
if ($LASTEXITCODE -ne 0) { throw "keytool 生成失败" }

# 构建脚本在拿不到环境变量时读这个文件（纯文本，只放在用户目录）
$props = Join-Path $dir "keystore.properties"
@(
    "storeFile=$Path"
    "storePassword=$pass"
    "keyAlias=$Alias"
    "keyPassword=$pass"
) | Set-Content $props -Encoding ASCII

# 用户级环境变量：以后开新终端就能直接构建 release 包
[Environment]::SetEnvironmentVariable("DOVE_ANDROID_KEYSTORE", $Path, "User")
$env:DOVE_ANDROID_KEYSTORE = $Path

Write-Host "==> 校验"
$info = & keytool -list -v -keystore $Path -storepass $pass -alias $Alias 2>&1
$info | Select-String -Pattern "SHA256|SHA-256|Valid|有效期|Owner|所有者" | ForEach-Object { "    " + $_.ToString().Trim() }
if ($LASTEXITCODE -ne 0) { throw "keytool 校验失败" }

Write-Host ""
Write-Host "==> 完成" -ForegroundColor Green
Write-Host "    keystore : $Path"
Write-Host "    alias    : $Alias"
Write-Host "    密码文件 : $props"
Write-Host "    环境变量 : DOVE_ANDROID_KEYSTORE（已写入用户作用域，新终端生效）"
Write-Host ""
Write-Host "！！！ 立刻把这两个文件备份到你自己的安全位置（U 盘 / 网盘 / 密码管理器）：" -ForegroundColor Yellow
Write-Host "    $Path" -ForegroundColor Yellow
Write-Host "    $props" -ForegroundColor Yellow
Write-Host "    丢了 keystore 就无法给已安装用户发布升级，只能换包名重来。" -ForegroundColor Yellow
