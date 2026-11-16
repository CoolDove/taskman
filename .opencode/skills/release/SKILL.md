---
name: Release
description: 给 TaskMan 出正式签名 APK 并发布 GitHub Release —— 版本号、产物命名、资产命名、release notes 写法与验证命令。
---

# TaskMan 发布流程

## 0. 前提

- 正式 keystore：`%USERPROFILE%\.android-keys\dove.jks`，alias `taskman`
  （由 `tools/make-keystore.ps1` 生成过一次；一个 keystore 可放多把 key，别的项目用新 alias）。
- 签名取用顺序（`build.ps1` / `build.sh` 一致）：
  1. `DOVE_ANDROID_KEYSTORE` 指向的文件；
  2. 环境变量缺失时读 `%USERPROFILE%\.android-keys\keystore.properties`；
  3. 都没有 → 退回仓库里的 `debug.keystore`，**这种包不许发布**。
- 密码 `DOVE_KEYSTORE_PASS`，alias `DOVE_KEY_ALIAS`（默认 `taskman`）。
- keystore、properties、密码**永远不进仓库**（`.gitignore` 已挡 `*.jks` / `keystore.properties` / `.android-keys/`）。

## 1. 版本号

只改 `app/src/main/AndroidManifest.xml`：

- `versionCode`：单调递增，必须大于上一次发布（当前 `30`）。
- `versionName`：等于 release 名去掉 `v` 前缀（release `v0.3` → `0.3`）。

## 2. 打包

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

产物 `build/TaskMan-release.apk`。**日志里出现黄字「未找到 release keystore，退回 debug 签名」就停下**，
那说明签名环境没生效。

## 3. 验证（必做）

```powershell
$bt = "$env:ANDROID_HOME\build-tools\36.0.0"
& "$bt\apksigner.bat" verify --print-certs build/TaskMan-release.apk
& "$bt\aapt2.exe" dump badging build/TaskMan-release.apk | Select-String '^package:'
```

期望：

- DN `CN=CoolDove, OU=Personal, O=CoolDove, C=CN`
- 证书 SHA-256 `ffd63f5c69aae6d6fba947cfdee5d2a0e7168c731b2cd0f9f8fa05650f851dd0`
  —— 换签名等于已安装用户永久无法升级，指纹变了就是踩雷。
- `versionCode` / `versionName` 与第 1 步一致。

## 4. 资产命名

`taskman-<version>.apk`，全小写，和仓库名同形：`taskman-0.3.apk`。

```powershell
Copy-Item build/TaskMan-release.apk build/taskman-0.3.apk
```

## 5. 发布

```powershell
git tag -a v0.3 -m "TaskMan 0.3"
git push origin main
git push origin v0.3
gh release create v0.3 build/taskman-0.3.apk --title "TaskMan 0.3" --notes-file .notes.md --latest
```

替换已发布版本里的资产（同名资产不能直接覆盖）：

```powershell
gh release delete-asset v0.3 <旧资产名> --yes
gh release upload v0.3 build/taskman-0.3.apk
```

只改 notes：

```powershell
gh release edit v0.3 --notes-file .notes.md
```

## 6. Release notes 规矩

**只写这次改了什么。** 一条一行，像 changelog，英文，名词短语或过去式。

```markdown
- Center-focused card carousel: middle card highlighted, neighbours peek in and scale/fade.
- Closing an app force-stops it and removes its task from recents.
- Light/dark theme follows the system and switches in place.
```

**不要写**：安装步骤、Shizuku 怎么配、按键表、已知限制、推荐应用、开场白、结尾客套、
「首次发布请多关照」这类话。用户会看仓库 README，release 只是变更清单。

## 7. 踩坑

- **tag 只能指向 main 上的 commit**：先 push commit 再 push tag；如果 tag 已经推上去才 rebase，
  得 `git push origin :refs/tags/v0.3` 删远端 tag，本地 `git tag -d v0.3` 后重打。
- PowerShell 里 `^` 是转义字符：`git rev-parse v0.3^{commit}` 会变成乱码参数。
  用 `git log -1 --format=%H v0.3`。
- **换签名后设备上必须先卸载**：`adb uninstall app.dsh.running`，会丢 Shizuku 授权（按包名记）。
  同一个 key 后续升级才能 `adb install -r` 覆盖。
- `gh release upload` 遇到同名资产会报错，先 `delete-asset`。
- PowerShell 里 `gh` / `git push` 把正常信息打到 stderr，`Select-Object` 管道下会显示成
  `NativeCommandError`，看末尾的 `xxx..xxx main -> main` 判断成败，不以退出码为准。
- 中文 commit message 用 `git commit -F 文件`（UTF-8 文件）更稳，别直接内联进 PowerShell 命令。
