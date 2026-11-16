# TaskMan · 横屏卡片版

> 要改代码 / 接手这个项目，先读 [AGENTS.md](AGENTS.md)：构建方式、设备 adb 流程、
> 已验证过的 Android 坑（为什么必须用 Shizuku、怎么避免启动闪一版错列表等）都在里面。

一个零依赖的 Android 小应用：强制横屏，用横向卡片轮播展示手机上活跃的应用。
中间的卡片永远居中并高亮，左右各露出半张，滚动手感带 easing。

- 应用名：TaskMan　包名：`app.dsh.running`　版本：1.8
- Java + 原生 View，依赖只有一个 `Shizuku API`（约 57 KB）
- minSdk 23，targetSdk 34，`screenOrientation="sensorLandscape"`

## 操作

| 输入 | 作用 |
| --- | --- |
| `←` `→`（手柄 D-Pad / 键盘方向键） | 上一张 / 下一张 |
| `A`（或回车、D-Pad 中键） | 切换到当前卡片的应用 |
| `X` | 结束当前卡片的应用（后台进程） |
| `Y` | 切换「任务列表」/「任务列表 + 已划掉」（界面上没有提示，纯快捷键） |
| 左右滑动 | 同上，松手自动吸附 |
| 点空状态里的按钮 | 切换前台/后台 |
| 点中间卡片 | 打开当前应用 |
| 点两侧露出的卡片 | 滑到那一张 |
| 长按卡片 | 打开系统应用详情页 |

## 列表从哪来：Shizuku

普通应用在 Android 5.0 之后拿不到别人的 task（实测 `getRecentTasks` / `getRunningAppProcesses`
都只返回自己），所以「和系统任务管理器一致」这件事必须借特权通道。

做法：通过 [Shizuku](https://shizuku.rikka.app/) 的 **UserService** 起一个跑在 shell（uid 2000）
下的进程，在里面执行 `dumpsys activity recents` 取真实任务栈；X 键则执行 `am force-stop`。
UserService 进程没有隐藏 API 限制，也不用改 targetSdk。

「结束」是两步：`am force-stop <pkg>` 杀掉进程，再 `am stack remove <taskId>` 把任务从最近任务里删掉。
只做 force-stop 的话进程死了但任务卡片还在，系统任务面板里会留一张点了要冷启动的卡。

没有 Shizuku 时自动回退到 `UsageStatsManager`（"仅前台/含后台"），功能不中断但列表只是近似。

### 启动时怎么尽快拿到正确列表

之前是「先渲染 UsageStats 兜底，再等服务连上换成任务栈」，所以开机第一秒会闪一版错误列表。现在：

1. `Application.onCreate` 里就开始 `bindUserService`（不等界面），用户服务进程用 `daemon(true)` 常驻，后续启动基本秒连；
2. 界面分三态：`任务列表` / `正在连接` / `仅前台`。**Shizuku 可用但服务还没连上时，不会拿 UsageStats 顶替**，而是先铺上次缓存的任务列表（卡片状态显示「任务」），连上后立刻换成实时数据；
3. 只有 Shizuku 确实不可用（没装/没跑/没授权）才走 UsageStats 兜底。

### 启动时的 Shizuku 检测

TaskMan 每次启动都会 `Shizuku.pingBinder()`：

- 已在运行 → 直接进「任务列表」模式
- 没在运行 → 顶部出现「Shizuku 未运行 · 点此打开」提示条，并**自动把 Shizuku 叫到前台**一次；
  之后每 1.5 秒检测一次，Shizuku 一起来就自动绑定用户服务、切回任务列表，不用手动刷新
- 没安装 → 提示条显示「未安装 Shizuku」，继续用「仅前台」的近似列表

这里有个绕不过的限制：**普通应用没有 shell 权限，无法代替 Shizuku 启动它自己的服务**
（那正是 Shizuku 存在的意义）。所以 TaskMan 能做的是「检测 → 拉起 Shizuku 界面 → 等它起来自动接管」，
服务本身要由 Shizuku 自己启动。

### 离开即关闭

`onPause()` 里调用 `finishAndRemoveTask()`：用 A 打开别的应用、回主屏、打开系统任务界面，
TaskMan 都会自己退出，并且不在最近任务里留下自己的卡片（进程会被系统回收，不用管）。

两个例外会保持存活：
- TaskMan 主动跳到 Shizuku 去启动它时
- 等 Shizuku 授权弹窗返回时

### 界面

- 空列表只显示一个「无任务」，没有说明文字和按钮（没授权时才出现「使用情况访问」按钮）
- 底部只保留 `A 打开` / `X 关闭` 两个提示
- 顶部只有 TaskMan 和应用数量，不再有模式胶囊

### 列表里不显示谁

- TaskMan 自己
- 桌面（launcher）
- Shizuku（`moe.shizuku.privileged.api`）——它只是工具，不算任务

### 启动 Shizuku（本机验证用的命令）

```bash
# 安装
adb install Shizuku.apk

# 用 shell 身份把服务拉起来（重启后需要重新执行）
APK=$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed 's/^package://')
adb shell "$(dirname $APK)/lib/arm64/libshizuku.so --apk=$APK"
```

应用首次启动会弹 Shizuku 授权框，允许即可；之后标题旁的胶囊显示「任务列表」。

## 布局与动画

- 卡片宽 = 屏宽 × 0.42，相邻卡片中心距 = 屏宽 ÷ 2，所以左右两张正好各露出半个卡片
  （实测 1920×1080 下：卡宽 806px，中心 959.5，屏心 960）。
- 卡片 ≥ 3 张时轮播是循环的，第一张的左边永远是最后一张，不会出现空位。
- 屏幕左右边缘各有一段 9% 宽的渐隐，两侧卡片不会像被硬切。
- 滚动位置是一个浮点数 `offset`，由 `ValueAnimator` + `PathInterpolator(0.2, 0, 0, 1)`
  驱动（340ms，先快后慢），拖拽松手后吸附到最近一张。
- 每帧按 `|i - offset|` 同时插值卡片的缩放（1 → 0.86）和透明度（1 → 0.42），
  中间卡片由 `CarouselView.dispatchDraw()` 画一圈绿色描边 + 外发光作为「聚焦」提示。
- 提示信息用应用内的小胶囊（深色），不用系统 Toast——掌机上系统 Toast 是盖住卡片的大白条。

## 为什么不是「真·进程列表」

Android 8.0 起 `getRunningAppProcesses()` 对普通应用只返回自己，
`getRunningServices()` 同样被限制。没有 root / Shizuku 时拿不到真实进程表，
所以这里用 `UsageStatsManager.queryEvents()` 还原「最近前台 → 是否又退到后台」，
这也是清理类应用普遍的做法。

「前台」= 最后一次使用事件是 `MOVE_TO_FOREGROUND`，或刚退到后台 3 分钟以内
（否则从别的应用切过来时，前台列表会永远是空的）。

`killBackgroundProcesses()` 只能结束**后台进程**；系统不允许普通应用结束别人的前台进程。
需要更强效果时，长按卡片跳到系统应用详情页点「强行停止」。

## 真机（AYN Thor / Android 13）已验证的行为

- 强制横屏 + 沉浸式全屏，无状态栏/导航栏
- ← → 滚动、A 打开、X 关闭、Y 切换（任务列表 3 ↔ 含已划掉 14）
- X 走 `am force-stop` + `am stack remove`，实测进程被杀（`pidof` 为空）且任务从 `dumpsys activity recents` 消失
- 列表顺序与 `dumpsys activity recents` 完全一致（设置 → The Gallery → Shizuku）
- 触屏滑动切换、松手吸附
- 卡片中心与屏幕中心的误差 < 1px

## 构建（不依赖 Gradle）

```bash
cd RunningApps
./build.sh
# 产物：build/RunningApps-debug.apk
```

脚本用 `aapt2 → javac → d8 → zipalign → apksigner` 手动打包，用的是本机 arm64 版
`/usr/lib/android-sdk/build-tools/debian/` 里的 aapt2/zipalign/apksigner，
以及 `/opt/android-sdk` 的 build-tools 34 与 android-34 平台。

工程同时带 `build.gradle` / `settings.gradle` / `app/build.gradle`，可直接在 Android Studio 打开。

## 安装与首次使用

1. 安装 APK（`adb install -r RunningApps-debug.apk`，或手机上点开文件安装）。
2. 打开应用，空状态里点「去开启使用情况访问」。
3. 在系统列表里找到「运行中应用」并打开开关，返回即可。

sideload 安装时可以用一条命令直接授权：

```bash
adb install -r build/RunningApps-debug.apk
adb shell appops set app.dsh.running GET_USAGE_STATS allow
adb shell am start -n app.dsh.running/.MainActivity
```

## 文件

```
RunningApps/
├── build.sh                       # 一键构建 + 签名
├── tools/make_icon.py             # 生成各密度启动图标（纯 zlib 写 PNG）
├── app/src/main/AndroidManifest.xml
├── libs/                          # Shizuku API 的 aar 解包（api/provider/shared/aidl）
├── app/src/main/java/app/dsh/running/
│   ├── MainActivity.java          # 界面、数据、手柄按键、任务栈解析
│   ├── CarouselView.java          # 居中卡片轮播 + easing 滚动 + 聚焦描边
│   ├── TaskUserService.java       # 跑在 shell 进程里的用户服务（dumpsys / force-stop）
│   └── Diag.java                  # 崩溃日志
└── app/src/main/res/
    ├── values/strings.xml
    ├── values/styles.xml
    └── mipmap-*/ic_launcher.png
```

## 崩溃排查

`Diag` 只在崩溃时把堆栈写到 `下载/DSH-Go/logs/`（MediaStore），并且 `onCreate` /
`onResume` 出错时会显示红底错误页，方便没有 adb 的时候定位问题。

已知踩过的坑：Android 14 上 `getWindow().getInsetsController()` 在 `setContentView()`
之前调用会在框架内部 NPE（`PhoneWindow.getInsetsController()` 直接解引用 mDecor），
所以沉浸式必须在 decor 创建之后再设置，并且要 try/catch。

## 后续可以加的东西
- 卡片里放应用截图（可以用 Shizuku 的 shell 权限拿 `TaskSnapshot`）
- 手柄振动反馈（`Vibrator` / `InputDevice.getVibrator()`）
- 长按 X 连续清理、白名单
