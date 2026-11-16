# build.sh 的 Windows 等价版：aapt2 → javac → d8 → zipalign → apksigner
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
# 说明：走 scoop 装的 android-clt（build-tools 36 + platforms/android-35），
#       不依赖 Gradle，产物与 build.sh 一致：build/TaskMan-debug.apk
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pkg = "app.dsh.running"
$out = Join-Path $root "build"

$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) {
    $sdk = Join-Path $env:USERPROFILE "scoop\apps\android-clt\current"
}
if (-not (Test-Path $sdk)) { throw "找不到 Android SDK，请设置 ANDROID_SDK_ROOT" }

# 取最高版本的 build-tools
$bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $bt) { throw "找不到 build-tools" }
$btPath = $bt.FullName

# 取最高版本的 platform（manifest 是 targetSdk 34，用 35 的 android.jar 编译没问题）
$plat = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName "android.jar") } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $plat) { throw "找不到 platforms/android-*/android.jar" }
$jar = Join-Path $plat.FullName "android.jar"

Push-Location $root
try {
    Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$out\res", "$out\gen", "$out\classes", "$out\dex" | Out-Null

    Write-Host "==> 编译资源"
    & "$btPath\aapt2.exe" compile --dir "$root\app\src\main\res" -o "$out\res"
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

    Write-Host "==> 链接 manifest + 资源"
    # manifest 不带 package 属性，和 build.sh 一样用文本替换注入
    (Get-Content "$root\app\src\main\AndroidManifest.xml" -Raw) -replace '<manifest ', "<manifest package=`"$pkg`" " |
        Set-Content "$out\AndroidManifest.xml" -NoNewline -Encoding utf8
    $res = @(Get-ChildItem "$out\res" -Filter *.flat | ForEach-Object { $_.FullName })
    & "$btPath\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$out\AndroidManifest.xml" `
        --java "$out\gen" @res --auto-add-overlay
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

    Write-Host "==> javac"
    # androidx.annotation 的 RestrictTo 枚举警告来自 Shizuku 的 jar，无害
    $deps = @("api", "provider", "shared", "aidl") |
        ForEach-Object { "$root\libs\$_-classes.jar" } | Where-Object { Test-Path $_ }
    $srcs = @(Get-ChildItem "$out\gen", "$root\app\src\main\java" -Recurse -Filter *.java |
        ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -classpath (($jar) + ";" + ($deps -join ";")) `
        -d "$out\classes" @srcs
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    Write-Host "==> d8 (dex)"
    # 依赖 jar 必须同时进 d8，否则运行时 NoClassDefFoundError
    $cls = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
    & "$btPath\d8.bat" --release --min-api 23 --lib $jar --output "$out\dex" @cls @deps
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    Write-Host "==> 打包"
    Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
    python -c "import zipfile; z=zipfile.ZipFile(r'$out\unsigned.apk','a',zipfile.ZIP_DEFLATED); z.write(r'$out\dex\classes.dex','classes.dex'); z.close()"
    if ($LASTEXITCODE -ne 0) { throw "追加 classes.dex 失败（需要 python3）" }

    # resources.arsc 必须 stored + 4 字节对齐，否则 Android 11+ 拒装
    & "$btPath\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    Write-Host "==> 签名"

    # 正式签名：DOVE_ANDROID_KEYSTORE 指向 release keystore（tools\make-keystore.ps1 生成），
    # 密码取 DOVE_KEYSTORE_PASS，没设就读 ~\.android-keys\keystore.properties。
    # 两者都没有才退回仓库里的 debug.keystore —— 那只是本地调试用，不能发布。
    $ksPath = $env:DOVE_ANDROID_KEYSTORE
    $ksPass = $env:DOVE_KEYSTORE_PASS
    $ksAlias = $env:DOVE_KEY_ALIAS
    if (-not $ksPath) {
        $props = Join-Path $env:USERPROFILE ".android-keys\keystore.properties"
        if (Test-Path $props) {
            Get-Content $props -Encoding ASCII | ForEach-Object {
                if ($_ -match '^\s*(#|$)') { return }
                $kv = $_.Split('=', 2)
                if ($kv.Count -lt 2) { return }
                $k = $kv[0].Trim(); $v = $kv[1].Trim()
                if ($k -eq 'storeFile') { $ksPath = $v }
                elseif ($k -eq 'storePassword' -and -not $ksPass) { $ksPass = $v }
                elseif ($k -eq 'keyAlias' -and -not $ksAlias) { $ksAlias = $v }
            }
        }
    }

    if ($ksPath -and (Test-Path $ksPath)) {
        if (-not $ksAlias) { $ksAlias = "taskman" }
        if (-not $ksPass) { throw "缺少 keystore 密码：设置 DOVE_KEYSTORE_PASS 或写进 keystore.properties" }
        $apkName = "TaskMan-release.apk"
        & "$btPath\apksigner.bat" sign --ks $ksPath --ks-key-alias $ksAlias `
            --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
            --out "$out\$apkName" "$out\aligned.apk"
        if ($LASTEXITCODE -ne 0) { throw "apksigner failed（密码不对或 alias 不存在）" }
        & "$btPath\apksigner.bat" verify "$out\$apkName" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }
        Write-Host "    正式密钥：$ksPath (alias $ksAlias)"
    } else {
        Write-Host "    未找到 release keystore，退回 debug 签名（仅供本机调试，不要发布）" -ForegroundColor Yellow
        $apkName = "TaskMan-debug.apk"
        $ks = "$root\debug.keystore"
        if (-not (Test-Path $ks)) {
            # 注意：换密钥会导致无法覆盖安装，必须先从原构建机拷来
            & keytool -genkeypair -keystore $ks -storepass android -keypass android `
                -alias debug -keyalg RSA -keysize 2048 -validity 10000 `
                -dname "CN=DSH Debug,O=DSH,C=CN"
        }
        & "$btPath\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
            --out "$out\$apkName" "$out\aligned.apk"
        if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
        & "$btPath\apksigner.bat" verify "$out\$apkName" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }
    }

    Write-Host "==> 完成"
    Get-Item "$out\$apkName" | Select-Object Name, Length, LastWriteTime
}
finally {
    Pop-Location
}
