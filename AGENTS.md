# AGENTS.md — TaskMan 项目上下文

给后续接手这个项目的 AI / 开发者的实操笔记。**这里的每条结论都是在这台设备上真机验证过的**，
照抄命令即可复现，不要凭常识推翻它们。

## 1. 项目是什么

横屏卡片轮播的「最近任务」管理器：中间一张卡片居中并高亮，左右各露半张，手柄 ←/→ 切换、
A 打开、X 关闭、Y 切换显示范围、START 熄屏退出。列表内容**必须等于系统任务管理器**，所以依赖
Shizuku 拿到真实任务栈（普通应用拿不到，见 §5）。

- 应用名：TaskMan　包名：`app.dsh.running`（包名不要改，改了就丢 Shizuku 授权）
- 版本：2.1（versionCode 21），minSdk 23 / targetSdk 34
- 验证设备：AYN Thor（`kalama`，Android 13，1920×1080 横屏掌机，内置 Odin Controller + Xbox 手柄）
- 构建/运行环境：aarch64 的 Ubuntu proot（DSH App 内），file sandbox 不可用，bash 一律
  `sandbox_permissions: danger-full-access`（参考 `~/.dsh/AGENTS.md`）

## 2. 目录结构

```
RunningApps/
├── build.sh                      # 一键构建（aapt2 → javac → d8 → zipalign → apksigner）
├── build.ps1                     # 同上，Windows 工作区用（scoop android-clt）
├── libs/                         # Shizuku 依赖：从 Maven aar 里解出来的 classes.jar
│   ├── api-classes.jar           # dev.rikka.shizuku:api:13.1.5
│   ├── provider-classes.jar      # :provider（ShizukuProvider）
│   ├── shared-classes.jar        # :shared
│   └── aidl-classes.jar          # :aidl（IShizukuService 等，api 的传递依赖）
├── app/src/main/java/app/dsh/running/
│   ├── App.java                  # Application：装崩溃日志 + 最早初始化 TaskBridge
│   ├── MainActivity.java         # 界面、三态数据、手柄按键、任务栈解析、缓存
│   ├── CarouselView.java         # 居中卡片轮播 + easing 滚动 + 渐变焦点环 + 边缘渐隐
│   ├── Theme.java                # 亮/暗两套配色（跟随系统 uiMode）
│   ├── Backdrop.java             # 背景：竖向渐变 + 两团环境光
│   ├── TaskBridge.java           # Shizuku 用户服务的连接管理（早绑定 / 重连 / dump / stop / sleep）
│   ├── TaskUserService.java      # 跑在 shell 进程里的服务：dumpsys / force-stop / stack remove / 熄屏
│   └── Diag.java                 # 崩溃时把堆栈写到 下载/DSH-Go/logs/
└── app/src/main/res/             # strings / styles / 手写 PNG 图标（tools/make_icon.py 生成）
```

## 3. 构建（不要引入 Gradle）

- **本机没有可用的 x86_64 SDK 工具**：`/opt/android-sdk/build-tools/34.0.0` 里只有 `d8`（jar）能跑，
  `aapt2` / `zipalign` 是 x86 二进制会报 “required file not found”。
  必须用 Debian 打包的 arm64 版本：`/usr/lib/android-sdk/build-tools/debian/{aapt2,zipalign,apksigner}`。
- `./build.sh` 一条命令出包，产物 `build/TaskMan-debug.apk`（约 57KB）。工程里的 `build.gradle`
  只是给 Android Studio 打开用的，不参与构建。
- manifest **不带 `package` 属性**（Gradle 用 namespace），build.sh 用 `sed` 注入后再 `aapt2 link`。
- 依赖 jar 必须**同时**出现在 `javac -classpath` 和 `d8` 的输入里，否则运行时 `NoClassDefFoundError`。
  缺 `aidl-classes.jar` 时 d8 会警告 `moe.shizuku.server.IShizukuService$Stub not found`。
- 没有 `zip` 命令时，build.sh 用 `python3 zipfile` 把 `classes.dex` 追加进 APK。
- `resources.arsc` 必须保持 stored + 4 字节对齐（`zipalign -f -p 4`），否则 Android 11+ 拒装。
- 图标是 `tools/make_icon.py` 用纯 zlib 手写 PNG 生成的（没有 Pillow），改图标重跑它即可。
  它一次产出三份：`ic_launcher.png`（传统整图，squircle 自带圆角，兜底 API 23~25）、
  `ic_launcher_background.png`（满铺渐变）、`ic_launcher_foreground.png`（只有卡片层，
  整体缩到 0.82 倍塞进 72dp 安全区），后两者由 `res/mipmap-anydpi-v26/ic_launcher.xml` 组合成自适应图标。

### Windows 侧构建（`build.ps1`）

上面的 `build.sh` 只能在 aarch64 Linux/proot 里跑。在 Windows 工作区（`F:\workspc\taskman`）用
`build.ps1`，它逐条等价于 `build.sh`，工具取自 scoop 的 `android-clt`（build-tools 36 的 x86_64 齐全）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

- 差异只有两处：`android.jar` 用 `platforms/android-35`（没有 34 也能编 targetSdk 34），
  以及没有 `zip` 时同样用 `python zipfile` 追加 `classes.dex`。
- 脚本必须存成 **UTF-8 with BOM**，Windows PowerShell 5.1 会按 GBK 解析无 BOM 的中文注释并报语法错。
- `javac` 的 `androidx.annotation.RestrictTo` 枚举警告来自 Shizuku 的 jar，无害，退出码仍是 0。

## 4. 设备与 adb 工作流

- 设备是 **AYN Thor**（不是跑 DSH 的那台手机）。通过无线调试连接：

```bash
adb pair <host>:<pairPort> <6位配对码>     # 弹窗里的端口 = 配对端口
adb connect <host>:<connectPort>           # 无线调试主页面上那个端口，和配对端口不是同一个
adb devices
```

- 配对码一次性；配对端口在弹窗关闭后失效。**proot 里 mDNS 不通**，`adb mdns services` 永远是空的，
  所以 connect 端口只能让用户从「无线调试」页面读给你。
- 常用动作：

```bash
adb install -r build/TaskMan-debug.apk
adb shell appops set app.dsh.running GET_USAGE_STATS allow
adb shell am start -n app.dsh.running/.MainActivity
adb shell input keyevent KEYCODE_DPAD_RIGHT   # 或 96=A 99=X 100=Y 187=APP_SWITCH
adb exec-out screencap -p > shot.png
adb shell dumpsys activity recents | grep -E '^\s+\* Recent #'
adb shell pidof <pkg>
```

- 重启后 adb 端口会变、**Shizuku 服务也会停**（它是 `adb shell` 拉起来的），都需要重新做。
- 应用会「离开即关闭」（`onPause` → `finishAndRemoveTask`），所以截图必须在它前台时抓；
  `am start` 之后再 `screencap`，中间别按 HOME。

## 5. 为什么必须用 Shizuku（已经实测排除过别的路）

普通应用在 Android 13 上拿不到别人的任务，三个 API 都只返回自己：

```
getRecentTasks        = app.dsh.running|
getRunningAppProcesses= app.dsh.running|
getRunningTasks       = app.dsh.running|
```

- 直接调隐藏接口 `android.app.IActivityTaskManager.getRecentTasks`：Android 13 直接拦
  `Accessing hidden method ... (blocked, linking, denied)` → `NoSuchMethodError`。
- **把 targetSdk 降到 27 也不放行**（照样 denied），而且 `IActivityTaskManager$Stub.asInterface`
  在这台机器的 framework 里 `NoSuchMethodError`。这条路不要再试。
- 结论：走 **Shizuku UserService**——服务进程以 shell(uid 2000) 身份运行，没有隐藏 API 限制，
  直接用 `Runtime.exec` 执行系统命令。

### 启动 Shizuku 服务（shell 身份）

```bash
APK=$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed 's/^package://')
adb shell "$(dirname $APK)/lib/arm64/libshizuku.so --apk=$APK"
adb shell ps -A | grep shizuku_server     # 有 shell 身份的 shizuku_server 就成功
```

### 应用侧接线

- manifest：`QUERY_ALL_PACKAGES`、`moe.shizuku.manager.permission.API_V23`、
  `ShizukuProvider`（authorities `app.dsh.running.shizuku`）、
  meta-data `moe.shizuku.client.V3_SUPPORT=true`。
- `Shizuku.bindUserService(args, conn)`，`processNameSuffix("task")`、`daemon(true)`（进程复用，
  连续启动是同一个 PID）、`version(3)`。
  **`daemon(true)` + 固定 `version` 会复用旧进程**：改了 `TaskUserService` 却不提版本号，
  新事务号在老进程里没人处理（`transact` 直接返回 false）。**服务端代码一改就把 version 加 1**。
- 和服务通信是**裸 binder 事务**（自定义 DESCRIPTOR + CODE_DUMP/CODE_STOP/CODE_SLEEP/CODE_SNAPSHOT），
  不生成 AIDL。
- `TaskUserService` 必须处理 `16777115` 销毁事务（`System.exit(0)`），否则用户服务进程会越积越多
  （曾经出现过 3 个 `app.dsh.running:task`）。
- 数据来源：`dumpsys activity recents` 文本。注意每个 task 块里的 `mActivityComponent=` 才是真包名，
  `A=10148:xxx` 是 affinity（设置会写成 `com.android.settings.root`，按它解析会漏应用）。

### 任务缩略图（CODE_SNAPSHOT）—— 为什么这条路能走通

系统本来就给每个任务存了截图（`TaskSnapshotController`），只是取的时候卡权限：

```java
// frameworks/base .../ActivityTaskManagerService.java (android-13.0.0_r1)
public TaskSnapshot getTaskSnapshot(int taskId, boolean isLowResolution) {
    mAmInternal.enforceCallingPermission(READ_FRAME_BUFFER, "getTaskSnapshot()");
```

- 普通应用没有 `READ_FRAME_BUFFER` → SecurityException（这就是「拿不到截图」的老结论）。
- 但 **`com.android.shell` 在 AOSP 清单里就声明了它**（"System tool permissions granted to the shell"），
  真机上 `dumpsys package com.android.shell | grep READ_FRAME_BUFFER` 是 `granted=true`。
  用户服务跑在 uid 2000，所以**能拿到真实截图**。
- 直接读 `/data/system_ce/0/snapshots/*.jpg` 不行：那是 DAC 权限，shell 连目录都进不去，只有 root 能读。
- 方法签名**按版本不一样**，必须反射遍历 `IActivityTaskManager.getTaskSnapshot`：
  本机（Thor / Android 13 ROM）是 `(int, boolean, boolean)`，AOSP 13 是 `(int, boolean)`。
  返回的 `TaskSnapshot` → `getHardwareBuffer()` → `Bitmap.wrapHardwareBuffer()` → 拷贝成软件位图。
- API < 29 没有 `HardwareBuffer` / `wrapHardwareBuffer` → 直接返回 null，卡片回退渐变块。
- 回传的是**缩到 480px 宽的 JPEG**：binder 单次事务上限 1MB，别塞原图（1920×1080 ARGB 是 8MB）。
  主进程再缩到 192px、盒式模糊 2 次（半径 w/32），当一个 ~15KB 的底图用，按包名缓存在内存里。

## 6. 已踩过的坑（现象 → 原因 → 做法）

1. **启动闪退**：`java.lang.NullPointerException ... PhoneWindow.getInsetsController()`。
   `setDecorFitsSystemWindows` / `getInsetsController` 必须在 `setContentView()` 之后调用
   （decor 还没创建时框架内部不判空），并且整段包 try/catch。
2. **卡片不居中**：根布局按 `WindowInsets` 加了左右 padding，而左右不对称（右侧有 216px 的
   cutout/导航区），把舞台挤偏。做法：舞台（CarouselView）保持满宽，只给 header/footer 加左右 inset。
   实测居中后 ring `x=[557,1362]`，中心 959.5，屏心 960。
3. **第一张卡片左边空着**：轮播不循环。改成 `count >= 3` 时循环（距离取模），`< 3` 时老老实实夹取。
4. **焦点蒙版**：轮播/卡片被 focus 时系统画一层灰白半透明 `defaultFocusHighlight`。
   做法：`setFocusable(false)` + `setFocusableInTouchMode(false)` + `setDefaultFocusHighlightEnabled(false)`。
   按键都在 `Activity.dispatchKeyEvent` 处理，不需要 View 焦点。
5. **系统 Toast 是大白条**，会盖住卡片。改用应用内深色小胶囊（`banner`）。
6. **关闭应用后 1 秒又出现、系统任务面板里还有卡**：`am force-stop` 只杀进程，**不删 task**。
   必须 `am stack remove <taskId>`（taskId 从 `Task*{...#682 ...}` 解析），顺序：先 force-stop 再 remove。
   另外系统应用（如「设置」）的强停会被系统忽略，adb 也一样，属正常。
7. **启动先闪一版错误列表**：先渲染 UsageStats 兜底、1~2 秒后才换成任务栈。修法见 §7「三态加载」。
8. **崩溃排查没有 adb 时**：`Diag` 在崩溃时把堆栈经 MediaStore 写到「下载/DSH-Go/logs/」；
   `Android/data/<pkg>` 在 proot 里读不到，别打那里的主意。`onCreate` 里出错会显示红底错误页。
9. **两侧卡片在屏幕内被「切断」**：`CarouselView` 的 `clipChildren` 在它自己边界裁切，
   而它必须是满宽的，两侧卡片正好各露半张、被**屏幕边缘**切。所以纵向容器的水平 padding 必须为 0，
   header / footer 各自单独加左右 padding（用 `max(l,r)` 抵消右侧 216px 的 cutout）。
10. **切夜间模式后「Quickstep 屡次停止运行」**：`com.android.launcher3` 的 `RecentsActivity` 在
   配置变更重建时重复注销 receiver（`IllegalArgumentException: Receiver not registered`），
   这是系统桌面的 bug，`cmd uimode night yes/no` 每次都可能崩一次，与 TaskMan 无关。
11. **缩略图偶发 `Canvas: trying to use a recycled bitmap`**：`Bitmap.createScaledBitmap()`
    在目标尺寸与原图完全相同时**返回原对象**而不是新图，照着「用完把旧图 recycle 掉」的写法
    会把它回收掉（之后 ImageView 一画就崩/空白）。做法：先判 `scaled != src` 再决定 recycle。
12. **PowerShell 里截图全是雪花/花屏**：`adb exec-out screencap -p > x.png` 会被 ps 按文本转码，
   PNG 直接损坏。必须 `cmd /c "adb ... > x.png"`。

## 7. 关键实现约定

### 轮播数学（CarouselView）
- `cardW = 屏宽 × 0.42`，`step = 屏宽 ÷ 2` ⇒ 左右相邻卡片正好各露一半（806px 卡宽 / 403px 可见）。
- `offset` 是浮点；`ValueAnimator` + `PathInterpolator(0.2, 0, 0, 1)`，340ms，先快后慢。
- 每帧按 `|i - offset|` 插值缩放 1→0.86、透明度 1→0.42；中心卡片由 `dispatchDraw` 画绿色描边 + 外发光。
- 两侧边缘各 9% 宽的渐隐（`LinearGradient`，颜色 `#0B0E13`）。

### 数据三态（MainActivity）
- `MODE_TASKS`（Shizuku 已连）→ `dumpsys` 任务栈
- `MODE_CONNECTING`（Shizuku 在但用户服务没连上）→ **只铺上次缓存，绝不拿 UsageStats 顶替**；
  没有缓存就显示 “Connecting…”
- `MODE_FALLBACK`（Shizuku 真不可用）→ UsageStats「仅前台/含后台」近似
- 尽早绑定：`App.onCreate` 里 `TaskBridge.init()`，早于界面；`TaskBridge.setOnReady` 回调触发 reload。
- 缓存：SharedPreferences `taskman/recent_pkgs`，每行 `pkg|lastActive`（旧格式只有 `pkg`，
  读的时候容错，时间缺省 0），所以冷启动铺缓存时卡片也能显示相对时间。
- 卡片状态行**只写时间**（`just now` / `N min ago`），前台那张写 “In foreground”；
  不要再用 “Task” / “Background” 这类前缀词。
- 界面文字**一律英文**（应用不跟随系统语言，`rel()` 也是手写的 “just now / N min ago”，
  别用 `DateUtils.getRelativeTimeSpanString()`，它会跟着系统 locale 变中文）。
- 过滤：自己、桌面 launcher、`moe.shizuku.privileged.api`。

### 手柄 / 触摸
- `DPAD_LEFT/RIGHT` 切换（长按按 repeat 限频）、`BUTTON_A(96)` / `DPAD_CENTER` / `ENTER` 打开、
  `BUTTON_X(99)` 关闭、`BUTTON_Y(100)` 切换显示范围；另外兼容键盘模式的字母 `A/X/Y`。
- `BUTTON_START(108)` → 熄屏休眠 + 结束自己。普通应用没有 `DEVICE_POWER`，只能让 Shizuku 用户服务
  以 shell 身份 `input keyevent 223`（`KEYCODE_SLEEP`，比 26 的电源键稳，只关不唤醒）。
  注入失败（用户服务没连上）时**不假装成功**：弹「熄屏需要先连上 Shizuku」并留在界面。
  熄屏必须在 `finishAndRemoveTask()` 之前发出，否则进程被回收、binder 事务会丢。
- 触摸：左右滑动跟手 + 甩动速度吸附（`snap()`：位移 > 0.30 张或速度 > 640dp/s 就翻页）；
  点中间打开、点两侧滑过去、长按进系统应用详情。
  长按的 `Intent` 必须带 `FLAG_ACTIVITY_NEW_TASK`，否则设置页会落在 TaskMan 的任务里，
  被 `finishAndRemoveTask()` 一起带走（现象：设置页闪一下就回到桌面/上一个应用）。
- **手势全部在 `CarouselView` 里判定**，`onInterceptTouchEvent` 对 `ACTION_DOWN` 直接返回 true，
  卡片子 View 不接触摸（否则子 View 拿到 DOWN 后父容器收不到 MOVE，横滑会被吃掉）。
- **上滑关闭**：上滑超过 touch slop 后锁定居中卡片，跟手上移 + 缩小 + 变淡（过半后有阻尼），
  松手时位移 > 0.22 张卡片高或上甩速度 > 650dp/s 就飞出去并淡出，否则弹回。
  退场动画结束才回调 `Listener.onDismiss(index)` → `MainActivity.closeIndex()`（先 force-stop 再 remove task）。
  退场动画期间新的触摸会被丢弃（`G_BLOCKED`），避免把卡片拽回来。
- **X 键与上滑共用同一套动效**：`closeCurrent()` 调 `carousel.dismissCurrent()`，不再直接改列表。
  焦点环（`dispatchDraw`）的透明度跟随卡片 alpha，否则卡片淡出了环还留着。
- **关闭后的重排动画**：`closeIndex` 先 `shown/all/taskList.remove` + `carousel.removeCard(index)`
  把卡片**就地抽走**（视图全部复用，不重建、不重贴缩略图），剩下的卡片用 320ms ease-out 补间到新位置。
  三个关键点，缺一个就会出现「闪一下 / 顿一下」：
  1. `removeViewAt` 会触发 `requestLayout`，所以 `onLayout` 里 **reflow 进行中必须跳过 `relayout()`**，
     否则会先画一帧「最终位置」再跳回起点做动画（就是那一下闪）。
  2. `force-stop` + `am stack remove` 是 binder + 两次 exec（100~300ms），**必须放后台线程**，
     否则退场动画结束后主线程被占住，画面静止 10 帧以上，看着像卡死。
  3. 焦点环跟着 reflow 淡入（`reflowAlpha = (f-0.55)/0.45`），不要一删就跳到旁边那张身上。
- **卡片之间的遮挡关系**：`setChildrenDrawingOrderEnabled(true)` + `getChildDrawingOrder()` 按
  「离中心越远越先画」排序，居中那张永远压在最上面。3 张关到 2 张时左边那张要跑到右边，
  有了这个画序它是**从居中卡片后面穿过去**的，不需要淡出淡入（那样会闪），左右键方向也保持原样。
- `reload()` 拿到的列表和当前一致时**不重建卡片**（`sameEntries()` 只比 pkg/taskId/resumed 等），
  重建会重新创建视图、打断正在跑的重排动画。

### 离开即关闭
- `onPause` 里 `finishAndRemoveTask()`，不留最近任务卡片。
- 两个例外保持存活：主动跳 Shizuku 启动（`keepAlive`）、等 Shizuku 授权弹窗
  （`TaskBridge.permissionDialogShowing()`）。

### 界面
- 结构（console 风格：渐变背景 + 悬浮卡片）：
  `Backdrop`（渐变 + 左上紫/右下蓝环境光）直接当背景，**没有外面的大框**：
  顶部一行淡标题（应用图标 + TaskMan，右侧数量胶囊）→ 中间轮播 → 位置指示器 → 底部按键胶囊。
- 卡片靠**投影**和背景分层：圆角 24dp、elevation（暗 14dp / 亮 10dp），
  API 28+ 用 `setOutlineAmbientShadowColor` / `SpotShadowColor` 调投影色。
  上半是缩略图位：**任务截图模糊铺底 + 96dp 图标压在上面**；拿不到截图（Shizuku 没连 /
  没有 snapshot / API < 29）就只剩主题渐变色块，功能不受影响。
  模糊底图 `alpha` 亮 0.82 / 暗 0.62，让卡片渐变色透上来，否则亮色截图会把卡片冲淡。
  缩略图容器必须 `setClipToOutline(true)`（outline 来自圆角背景），否则方角会盖住卡片圆角。
- 状态行只写相对时间（前台那张是 `In foreground`），**不要**再写 “Task” / “Background” 前缀。
- 居中那张由 `CarouselView.dispatchDraw` 画紫→粉渐变描边 + 三层递减透明度的外发光。
- 底部 `A 打开` / `X 关闭` / `START 熄屏` 是胶囊，颜色跟着主题走（亮色白胶囊 / 暗色深胶囊），
  按键标记用 accent 色的小圆角块；应用内提示条同理（亮色深底、暗色浅底）。
- 位置指示器：当前那张是长条，其余是小圆点；超过 10 个退化成「3 / 12」。
- 空列表只显示「无任务」（没授权时才出现「使用情况访问」按钮）。

### 亮/暗主题
- 所有颜色都在 `Theme.of(context)` 里，按 `Configuration.uiMode & UI_MODE_NIGHT_MASK` 选一套；
  **不要在别处写死色值**（`C_*` 常量已经删掉了）。
- 跟随系统切换：manifest 里接管 `uiMode`，`onConfigurationChanged` 里 `t = Theme.of(this)` +
  `setContentView(buildUi())` + `buildCards()` 原地重建，并保留当前选中位置。
  **不能用 `recreate()`**：它会走 `onPause`，而 onPause 里是 `finishAndRemoveTask`，界面会直接消失。
- `CarouselView.setTheme()` 同步焦点环颜色；两侧不再画「渐隐遮罩」——背景是渐变，
  糊一层纯色只会出现色带。侧边卡片靠 alpha（暗 0.55 / 亮 0.85）自然退后。

## 8. 改完必须跑一遍的验证

```bash
# 1) 冷启动不闪错误列表（先杀掉用户服务模拟冷启动）
adb shell "pkill -f 'app.dsh.running:task'"
adb shell am force-stop app.dsh.running
adb shell am start -n app.dsh.running/.MainActivity
sleep 0.4;  adb exec-out screencap -p > t1.png     # 首帧就应该是正确列表
sleep 3;    adb exec-out screencap -p > t2.png     # 与 t1 内容一致

# 2) X 真正结束应用
adb shell pidof <pkg>            # 关闭前有 PID
adb shell input keyevent 99      # X
adb shell pidof <pkg>            # 应为空
adb shell dumpsys activity recents | grep -c <pkg>   # 应为 0

# 3) A 打开并从任务栈消失
adb shell input keyevent 96
adb shell dumpsys window | grep mCurrentFocus        # 焦点应切到目标应用
adb shell dumpsys activity recents | grep -c 'A=10148:app.dsh.running'   # 应为 0
# 10148 是这台机器上 app.dsh.running 的 uid，重装后可能变，换机器更要改

# 4) START 熄屏并退出
adb shell dumpsys power | grep mWakefulness=        # Awake
adb shell input keyevent 108                        # START
sleep 1.5
adb shell dumpsys power | grep mWakefulness=        # Asleep
adb shell dumpsys activity recents | grep -c 'app.dsh.running/.MainActivity'   # 应为 0
adb shell input keyevent 224                        # 唤醒
adb logcat -d -s TaskSvc                            # 应有 "sleep -> "

# 5) 亮/暗主题跟随系统（原地切换，不能重启 Activity）
adb shell cmd uimode night yes                      # 立刻变暗色，列表和选中位置不变
adb shell cmd uimode night no                       # 变回亮色
# 截图注意：PowerShell/ps 的 `>` 会把 PNG 写坏，必须走 cmd
cmd /c "adb exec-out screencap -p > shot.png"
# 图太大读不进上下文时先缩一下（不需要 Pillow）
ffmpeg -y -i shot.png -vf scale=1152:-1 -q:v 4 shot.jpg

# 6) 触屏：横滑翻页 / 上滑关闭（1920x1080 的坐标，别的分辨率按比例改）
adb shell input swipe 1200 540 600 540 180        # 左滑 → 选中下一张（指示器前进）
adb shell input swipe 600 540 1300 540 180        # 右滑 → 回上一张
adb shell input swipe 960 800 960 300 260         # 上滑 → 卡片飞出，应用被结束
adb shell pidof <pkg>                             # 应为空
adb shell dumpsys activity recents | grep -c <pkg>   # 应为 0
# 慢速小幅上滑（滑不到位）应弹回，不关闭：
adb shell input swipe 960 700 960 640 600
```

排查按键「没反应」时的两个陷阱：

- **双屏**：这台机器 launcher 在 display 0、当前应用可能落在副屏，`adb shell input keyevent`
  默认注入 display 0，会打到 launcher 上。先 `dumpsys window | grep mCurrentFocus` 确认两行里
  哪一行是 TaskMan，必要时用 `am start` 把它拉到前台重试。
- **用户服务版本**：见 §5，改过 `TaskUserService` 就必须提 `.version(n)`，否则老 daemon 进程
  收到未知事务号只会返回 false（表现为「按了没反应、也没有日志」）。

## 9. 发布

- 版本号只改 `app/src/main/AndroidManifest.xml` 的 `versionCode` / `versionName`。
- 产物名跟着签名走：**有 release keystore → `build/TaskMan-release.apk`**，
  否则 `build/TaskMan-debug.apk`。交付时复制到 `/sdcard/Download/DSH-Go/`
  （注意：那是 **DSH 宿主手机**的存储，不是 Thor 的；Thor 通过 `adb install` 装）。
- 包名不变 → 覆盖安装不会丢 Shizuku 授权。**换签名密钥则一定会丢**（必须先卸载）。

### 正式签名（release keystore）

- 一次性生成（密码当场输，不回显）：`powershell -NoProfile -ExecutionPolicy Bypass -File tools\make-keystore.ps1`
  - 产物在 `%USERPROFILE%\.android-keys\dove.jks`（alias `taskman`，RSA 4096 / SHA256withRSA，
    validity 10000 天，DN `CN=CoolDove, O=CoolDove, C=CN`），**不在仓库里**（`.gitignore` 也挡了
    `*.jks` / `keystore.properties` / `.android-keys/`）。
  - 同时写 `%USERPROFILE%\.android-keys\keystore.properties`（含密码）并设置用户级环境变量
    `DOVE_ANDROID_KEYSTORE`。
- 构建脚本的取用顺序（`build.ps1` / `build.sh` 一致）：
  1. `DOVE_ANDROID_KEYSTORE` 指向的 keystore；
  2. `build.ps1` 在环境变量缺失时读 `%USERPROFILE%\.android-keys\keystore.properties`；
  3. 都没有 → 退回仓库里的 `debug.keystore`（只许本机调试，**不要发布**）。
  密码：`DOVE_KEYSTORE_PASS`，alias：`DOVE_KEY_ALIAS`（默认 `taskman`）。
- release 包一旦发出去，**这个 keystore + 密码就是唯一升级路径**：丢了只能换包名重来。
  `make-keystore.ps1` 会打印 SHA-256 指纹，发版时记下来，以后能核对签名是否为同一把。
- 用 `apksigner verify --print-certs` 自查：debug 包是 `CN=DSH Debug`，正式包应是 `CN=CoolDove`。
- 完整的出包 / 发版步骤（版本号、产物与资产命名 `taskman-<version>.apk`、notes 只写变更、
  验证命令、踩坑）见项目技能 `.opencode/skills/release/SKILL.md`，发版前先读它。

## 10. 未做 / 已知限制

- 普通应用无法启动 Shizuku 服务（那需要 shell/root）。TaskMan 只能「把 Shizuku 叫到前台 + 等它起来自动接管」；
  重启后仍需用 adb 命令或 Shizuku 自己的无线调试启动一次。想彻底自动化只能 root。
- 缩略图是系统存的**最后一张**快照，不实时更新（实现见 §5「任务缩略图」）。Shizuku 没连上 /
  API < 29 时卡片只有主题渐变色块；底图只在当前进程内存里缓存，重新进 TaskMan 会重新取一次。
