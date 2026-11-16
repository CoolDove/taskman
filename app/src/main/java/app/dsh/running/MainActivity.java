package app.dsh.running;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * 横屏卡片轮播版：中间卡片永远居中并高亮，左右各露出半张。
 *
 * 手柄：← → 切换　A 打开　X 关闭　Y 切换「只显示前台 / 显示后台」　START 熄屏并退出
 * 触屏：左右滑动切换，上滑关闭当前应用（和 X 键同一套退场动效），
 *       点中间卡片打开，点两侧滑过去，长按进应用详情。
 */
public class MainActivity extends Activity implements CarouselView.Listener {

    private static final long WINDOW_MS = 6L * 60L * 60L * 1000L;   // 看多远的使用记录
    private static final long HOT_MS = 3L * 60L * 1000L;            // 刚退到后台多久内仍算前台

    /** 当前配色，跟随系统亮/暗（onConfigurationChanged 里换一套并重建界面）。 */
    private Theme t;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<AppEntry> all = new ArrayList<>();
    private final ArrayList<AppEntry> shown = new ArrayList<>();

    private CarouselView carousel;
    private View headerBar;
    private View footerBar;
    private LinearLayout dotsRow;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptySub;
    private TextView permBtn;
    private TextView modeBtn;
    private TextView countView;
    private TextView banner;
    private View shizukuHint;
    private TextView shizukuHintText;
    private boolean shizukuLaunchTried;

    private boolean showBackground;
    private boolean loading;
    private String launcherPkg;

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private static final int MODE_CONNECTING = 0;
    private static final int MODE_TASKS = 1;
    private static final int MODE_FALLBACK = 2;

    private int mode = MODE_CONNECTING;
    private boolean keepAlive;
    private final ArrayList<AppEntry> taskList = new ArrayList<>();
    private final ArrayList<AppEntry> cacheList = new ArrayList<>();

    private static final String PREFS = "taskman";
    private static final String KEY_PKGS = "recent_pkgs";

    /** 已解码并模糊好的缩略图底图，按包名缓存（亮暗切换重建界面时不用重新拉）。 */
    private final Map<String, Bitmap> artCache = new HashMap<>();

    private final Runnable shizukuPoll = new Runnable() {
        @Override
        public void run() {
            boolean up = Shizuku.pingBinder();
            updateShizukuHint(up);
            if (up) {
                if (mode != MODE_TASKS && TaskBridge.ready()) {
                    reload();
                }
            } else {
                maybeLaunchShizuku();
            }
            ui.postDelayed(this, 1500);
        }
    };

    static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        long lastActive;
        long lastEvent;
        boolean resumed;
        boolean fromTask;
        boolean cached;
        int taskId = -1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            t = Theme.of(this);
            setupWindow();
            launcherPkg = resolveLauncher();
            setContentView(buildUi());
            // decor 此时才存在，hideBars() 必须放在 setContentView 之后
            hideBars();
            TaskBridge.init(getApplicationContext());
            TaskBridge.setOnReady(() -> ui.post(this::reload));
            ui.post(shizukuPoll);
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    /**
     * 系统切换亮/暗色时重建界面。
     *
     * 不能用 recreate()：它会让 Activity 走 onPause，而 onPause 里是 finishAndRemoveTask，
     * 界面会直接消失。所以 manifest 里接管 uiMode，原地重搭视图、保留选中位置。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            int keep = carousel == null ? 0 : carousel.getSelected();
            t = Theme.of(this);
            setContentView(buildUi());
            hideBars();
            buildCards();
            if (carousel != null) {
                carousel.select(Math.min(keep, Math.max(0, carousel.count() - 1)));
            }
        } catch (Throwable ex) {
            showCrash(ex);
        }
    }

    @Override
    protected void onDestroy() {
        TaskBridge.setOnReady(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepAlive = false;
        try {
            hideBars();
            updateShizukuHint(Shizuku.pingBinder());
            ui.removeCallbacks(shizukuPoll);
            ui.post(shizukuPoll);
            reload();
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(shizukuPoll);
        // 切到别的应用 / 回主屏 / 打开任务界面，一律结束并把自己的任务从最近任务里去掉
        if (!keepAlive && !TaskBridge.permissionDialogShowing()) {
            try {
                finishAndRemoveTask();
            } catch (Throwable ignored) {
            }
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBars();
        }
    }

    private void showCrash(Throwable t) {
        String trace = Log.getStackTraceString(t);
        Diag.save("ui-fatal", t);
        try {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.setBackgroundColor(0xFF160B0B);
            TextView tv = new TextView(this);
            tv.setText("Startup error — please send me this:\n\n" + trace);
            tv.setTextColor(0xFFFFB4B4);
            tv.setTextSize(9);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            tv.setTextIsSelectable(true);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 窗口

    private void setupWindow() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
        // 这里不要碰 getInsetsController()：decor 还没创建，框架内部会 NPE
    }

    private void hideBars() {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable ignored) {
            // 沉浸式失败不影响使用
        }
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        // 背景：渐变 + 两团环境光
        Backdrop backdrop = new Backdrop(this);
        backdrop.setPalette(t.bgTop, t.bgBottom, t.glowA, t.glowB);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 大面板已去掉：渐变直接当背景，卡片浮在上面
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        headerBar = buildHeader();
        content.addView(headerBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 舞台必须满宽：左右各露半张卡片，靠屏幕边缘裁切（有 padding 的话会提前裁断）
        FrameLayout stage = new FrameLayout(this);
        content.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        carousel = new CarouselView(this);
        carousel.setTheme(t);
        carousel.setListener(this);
        stage.addView(carousel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        banner = new TextView(this);
        banner.setTextColor(t.bannerText);
        banner.setTextSize(12);
        banner.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable bbg = round(t.bannerBg, 22);
        if ((t.bannerStroke >>> 24) != 0) {
            bbg.setStroke(Math.max(1, dp(1)), t.bannerStroke);
        }
        banner.setBackground(bbg);
        banner.setAlpha(0f);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(4);
        stage.addView(banner, blp);

        shizukuHint = buildShizukuHint();
        shizukuHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(4);
        stage.addView(shizukuHint, hlp);

        emptyView = buildEmpty();
        emptyView.setVisibility(View.GONE);
        stage.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 卡片下方的位置指示器
        dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        content.addView(dotsRow, dlp);

        // 底部：按键胶囊
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(10), 0, dp(2));
        footer.addView(keyHint("A", "Open"));
        footer.addView(keyHint("X", "Close"));
        footer.addView(keyHint("START", "Sleep"));
        footerBar = footer;
        content.addView(footer);

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l = 0, top = 0, r = 0, b = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left;
                top = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            // 左右不对称的 cutout（右侧 216px）会把标题、页脚挤偏；舞台保持满宽不受影响
            int side = dp(28) + Math.max(l, r);
            content.setPadding(0, top + dp(8), 0, b + dp(12));
            if (headerBar != null) {
                headerBar.setPadding(side + dp(6), dp(2), side + dp(6), dp(2));
            }
            if (footerBar != null) {
                footerBar.setPadding(side, dp(10), side, dp(2));
            }
            return insets;
        });
        return root;
    }

    /** 顶部标题行：应用图标 + TaskMan，右侧数量。没有外框，直接压着渐变。 */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(2), dp(6), dp(2));

        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Throwable ignored) {
        }
        header.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView title = new TextView(this);
        title.setText("TaskMan");
        title.setTextColor(t.text);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(10);
        header.addView(title, tlp);

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        countView = new TextView(this);
        countView.setTextColor(t.sub);
        countView.setTextSize(11);
        countView.setPadding(dp(10), dp(4), dp(10), dp(4));
        countView.setBackground(round(Theme.alphaOf(t.card, 0xE6), 11));
        header.addView(countView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private View buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(10), dp(24), dp(10));

        emptyTitle = new TextView(this);
        emptyTitle.setText("No tasks");
        emptyTitle.setTextColor(t.sub);
        emptyTitle.setTextSize(16);
        emptyTitle.setGravity(Gravity.CENTER);
        box.addView(emptyTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptySub = new TextView(this);
        emptySub.setTextColor(t.sub);
        emptySub.setTextSize(11);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(0, dp(6), 0, 0);
        emptySub.setVisibility(View.GONE);
        box.addView(emptySub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        permBtn = pill("Grant usage access", t.ringA, Theme.alphaOf(t.ringA, 0x1F), 12, 16, 8);
        permBtn.setVisibility(View.GONE);
        permBtn.setOnClickListener(v -> openUsageAccess());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(14);
        box.addView(permBtn, plp);

        modeBtn = pill("Show background apps (Y)", t.text, t.card, 12, 16, 8);
        modeBtn.setVisibility(View.GONE);
        modeBtn.setOnClickListener(v -> toggleMode());
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp2.topMargin = dp(10);
        box.addView(modeBtn, mlp2);
        modeBtn.setVisibility(View.GONE);
        return box;
    }

    /** 底部按键胶囊：按键标记 + 说明，颜色跟随主题（暗色下是深色底，别留成白条）。 */
    private View keyHint(String key, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(8), dp(6), dp(14), dp(6));
        GradientDrawable bg = round(t.pillBg, 16);
        if ((t.pillStroke >>> 24) != 0) {
            bg.setStroke(Math.max(1, dp(1)), t.pillStroke);
        }
        box.setBackground(bg);

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(t.pillKey);
        k.setTextSize(9);
        k.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        k.setGravity(Gravity.CENTER);
        k.setMinWidth(dp(18));
        k.setPadding(dp(5), dp(2), dp(5), dp(2));
        k.setBackground(round(Theme.alphaOf(t.pillKey, t.dark ? 0x30 : 0x22), 9));
        box.addView(k, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(t.pillText);
        l.setTextSize(11);
        l.setPadding(dp(7), 0, 0, 0);
        box.addView(l);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    // ------------------------------------------------------------- 数据

    private void reload() {
        if (loading) {
            return;
        }
        loading = true;
        final boolean granted = hasUsageAccess();
        final boolean ready = TaskBridge.ready();
        final boolean connecting = !ready && TaskBridge.connecting();
        final String cachedPkgs = connecting ? loadCachedPkgs() : null;

        new Thread(() -> {
            List<AppEntry> usage = null;
            List<AppEntry> tasks = null;
            List<AppEntry> cache = null;
            Throwable error = null;
            try {
                if (ready) {
                    String dump = TaskBridge.dumpRecents();
                    if (dump != null) {
                        tasks = parseRecents(dump);
                    }
                    // Y 键的「已划掉」要用
                    if (granted) {
                        usage = queryActive();
                    }
                } else if (connecting) {
                    // 服务还在连：先用上次缓存，绝不拿 UsageStats 冒充任务列表
                    cache = resolvePkgs(cachedPkgs);
                } else {
                    usage = granted ? queryActive() : new ArrayList<>();
                }
            } catch (Throwable t) {
                error = t;
            }
            final List<AppEntry> u = usage;
            final List<AppEntry> t = tasks;
            final List<AppEntry> c = cache;
            final Throwable fail = error;
            ui.post(() -> {
                loading = false;
                if (carousel != null && carousel.isDismissing()) {
                    // 退场动画只有 200ms 左右：等它收尾再重铺，避免把正在飞出去的卡片抽走
                    ui.postDelayed(this::reload, 180);
                    return;
                }
                if (fail != null) {
                    showCrash(fail);
                    return;
                }
                all.clear();
                if (u != null) {
                    all.addAll(u);
                }
                taskList.clear();
                cacheList.clear();
                if (t != null) {
                    taskList.addAll(t);
                    mode = MODE_TASKS;
                    saveCachedPkgs(t);
                } else if (c != null) {
                    cacheList.addAll(c);
                    mode = MODE_CONNECTING;
                } else {
                    mode = MODE_FALLBACK;
                }
                try {
                    buildCards();
                } catch (Throwable x) {
                    showCrash(x);
                }
            });
        }, "scan").start();
    }

    private String loadCachedPkgs() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PKGS, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void saveCachedPkgs(List<AppEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : list) {
            // pkg|lastActive：有时间和没有时间的旧缓存都能读（旧格式只有 pkg）
            sb.append(e.pkg).append('|').append(e.lastActive).append('\n');
        }
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PKGS, sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private List<AppEntry> resolvePkgs(String pkgs) {
        List<AppEntry> out = new ArrayList<>();
        if (pkgs == null || pkgs.isEmpty()) {
            return out;
        }
        PackageManager pm = getPackageManager();
        for (String line : pkgs.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String pkg = line;
            long ts = 0;
            int bar = line.indexOf('|');
            if (bar > 0) {
                pkg = line.substring(0, bar);
                try {
                    ts = Long.parseLong(line.substring(bar + 1).trim());
                } catch (NumberFormatException ignored) {
                    ts = 0;
                }
            }
            AppEntry e = entryFor(pm, pkg);
            if (e != null) {
                e.fromTask = true;
                e.cached = true;
                e.lastActive = ts;
                out.add(e);
            }
        }
        return out;
    }

    private List<AppEntry> parseRecents(String dump) {
        List<AppEntry> out = new ArrayList<>();
        if (dump == null) {
            return out;
        }
        List<String[]> blocks = new ArrayList<>();
        String header = null;
        StringBuilder body = new StringBuilder();
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("* Recent #")) {
                if (header != null) {
                    blocks.add(new String[]{header, body.toString()});
                }
                header = t;
                body.setLength(0);
            } else if (header != null) {
                body.append(line).append('\n');
            }
        }
        if (header != null) {
            blocks.add(new String[]{header, body.toString()});
        }

        PackageManager pm = getPackageManager();
        long now = System.currentTimeMillis();
        for (String[] block : blocks) {
            String head = block[0];
            String text = block[1];
            String type = between(head, "type=", " ");
            // mActivityComponent 才是真实包名；A= 后面是 affinity（设置会写成 com.android.settings.root）
            String comp = between(text, "mActivityComponent=", "\n");
            String pkg = null;
            if (comp != null) {
                int slash = comp.indexOf('/');
                if (slash > 0) {
                    pkg = comp.substring(0, slash).trim();
                }
            }
            if (pkg == null || pkg.isEmpty()) {
                String owner = between(head, "A=", "}");
                if (owner == null) {
                    continue;
                }
                int colon = owner.indexOf(':');
                pkg = colon >= 0 ? owner.substring(colon + 1) : owner;
            }
            if (pkg.isEmpty() || "home".equals(type) || "recents".equals(type)) {
                continue;
            }
            if (pkg.equals(getPackageName()) || pkg.equals(SHIZUKU_PKG)
                    || pkg.equals(launcherPkg)) {
                continue;
            }
            if (containsPkg(out, pkg)) {
                continue;
            }
            AppEntry e = entryFor(pm, pkg);
            if (e == null) {
                continue;
            }
            e.fromTask = true;
            e.taskId = parseTaskId(head);
            long inactive = parseInactive(text);
            e.lastActive = inactive >= 0 ? now - inactive : now;
            out.add(e);
        }
        return out;
    }

    /** "Task{ce7d85e #682 type=standard ...}" -> 682 */
    private static int parseTaskId(String head) {
        int brace = head.indexOf("Task{");
        String s = brace >= 0 ? head.substring(brace) : head;
        int hash = s.indexOf('#');
        if (hash < 0) {
            return -1;
        }
        int i = hash + 1;
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        try {
            return Integer.parseInt(s.substring(i, j));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** "lastActiveTime=... (inactive for 77s)" -> 77000 */
    private static long parseInactive(String text) {
        int i = text.indexOf("inactive for ");
        if (i < 0) {
            return -1;
        }
        i += "inactive for ".length();
        int j = i;
        while (j < text.length() && Character.isDigit(text.charAt(j))) {
            j++;
        }
        if (j == i) {
            return -1;
        }
        try {
            return Long.parseLong(text.substring(i, j)) * 1000L;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String between(String s, String from, String to) {
        int i = s.indexOf(from);
        if (i < 0) {
            return null;
        }
        i += from.length();
        int j = s.indexOf(to, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }

    private static int currentUserId() {
        return Process.myUid() / 100000;
    }

    private boolean containsPkg(List<AppEntry> list, String pkg) {
        for (AppEntry e : list) {
            if (e.pkg.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private AppEntry entryFor(PackageManager pm, String pkg) {
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (Exception ex) {
            return null;
        }
        AppEntry e = new AppEntry();
        e.pkg = pkg;
        e.label = pm.getApplicationLabel(ai).toString();
        Drawable icon;
        try {
            icon = pm.getApplicationIcon(ai);
        } catch (Exception ex) {
            icon = null;
        }
        e.icon = icon != null ? icon : pm.getDefaultActivityIcon();
        return e;
    }

    private void buildCards() {
        if (carousel == null) {
            return;
        }
        String keep = currentPkg();
        shown.clear();
        if (mode == MODE_TASKS) {
            shown.addAll(taskList);
            if (showBackground) {
                for (AppEntry e : all) {
                    if (!containsPkg(shown, e.pkg)) {
                        shown.add(e);
                    }
                }
            }
        } else if (mode == MODE_CONNECTING) {
            shown.addAll(cacheList);
        } else {
            for (AppEntry e : all) {
                if (showBackground || isForeground(e)) {
                    shown.add(e);
                }
            }
        }

        List<View> cards = new ArrayList<>();
        for (AppEntry e : shown) {
            cards.add(buildCard(e));
        }
        int initial = 0;
        if (mode == MODE_FALLBACK && keep != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).pkg.equals(keep)) {
                    initial = i;
                    break;
                }
            }
        }
        carousel.setCards(cards, initial);

        boolean empty = shown.isEmpty();
        carousel.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            updateEmpty();
        }
        updateHeader();
        updateDots();
    }

    /** 位置指示器：当前那张是长条，其余是小圆点；太多就退化成「3 / 12」。 */
    private void updateDots() {
        if (dotsRow == null) {
            return;
        }
        dotsRow.removeAllViews();
        int n = shown.size();
        if (n <= 1) {
            dotsRow.setVisibility(View.GONE);
            return;
        }
        dotsRow.setVisibility(View.VISIBLE);
        int sel = carousel == null ? 0 : carousel.getSelected();
        if (n > 10) {
            TextView tv = new TextView(this);
            tv.setText((sel + 1) + " / " + n);
            tv.setTextSize(10);
            tv.setTextColor(t.sub);
            dotsRow.addView(tv);
            return;
        }
        for (int i = 0; i < n; i++) {
            boolean on = i == sel;
            View d = new View(this);
            d.setBackground(round(on ? t.ringA : Theme.alphaOf(t.sub, 0x4D), 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    on ? dp(18) : dp(6), dp(6));
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            dotsRow.addView(d, lp);
        }
    }

    private View buildShizukuHint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable bg = round(t.warnBg, 18);
        bg.setStroke(Math.max(1, dp(1)), t.warnStroke);
        box.setBackground(bg);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(t.warnDot);
        dot.setBackground(dotBg);
        box.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        shizukuHintText = new TextView(this);
        shizukuHintText.setTextColor(t.warnText);
        shizukuHintText.setTextSize(12);
        shizukuHintText.setPadding(dp(9), 0, 0, 0);
        box.addView(shizukuHintText);

        box.setOnClickListener(v -> openShizuku());
        return box;
    }

    private void updateShizukuHint(boolean up) {
        if (shizukuHint == null) {
            return;
        }
        if (up) {
            shizukuHint.setVisibility(View.GONE);
            return;
        }
        shizukuHint.setVisibility(View.VISIBLE);
        if (isShizukuInstalled()) {
            shizukuHintText.setText("Shizuku is not running · tap to open");
        } else {
            shizukuHintText.setText("Shizuku not installed · task list unavailable");
        }
    }

    private boolean isShizukuInstalled() {
        try {
            return getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 应用自己无法以 shell 身份启动 Shizuku，只能把它叫到前台，服务由它自己拉起。 */
    private void maybeLaunchShizuku() {
        if (shizukuLaunchTried || !isShizukuInstalled()) {
            return;
        }
        shizukuLaunchTried = true;
        openShizuku();
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
            if (i != null) {
                keepAlive = true;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                toast("Shizuku not found");
            }
        } catch (Throwable t) {
            toast("Can't open Shizuku");
        }
    }

    private void updateEmpty() {
        boolean granted = hasUsageAccess();
        permBtn.setVisibility(mode == MODE_FALLBACK && !granted ? View.VISIBLE : View.GONE);
        modeBtn.setVisibility(View.GONE);
        emptySub.setVisibility(View.GONE);
        if (mode == MODE_CONNECTING) {
            emptyTitle.setText("Connecting…");
            emptyTitle.setTextColor(t.sub);
            emptyTitle.setTextSize(16);
        } else if (mode == MODE_FALLBACK && !granted) {
            emptyTitle.setText("Usage access required");
            emptyTitle.setTextColor(t.text);
            emptyTitle.setTextSize(15);
        } else {
            emptyTitle.setText("No tasks");
            emptyTitle.setTextColor(t.sub);
            emptyTitle.setTextSize(16);
        }
    }

    private void updateHeader() {
        if (countView == null) {
            return;
        }
        boolean empty = shown.isEmpty();
        countView.setVisibility(empty ? View.GONE : View.VISIBLE);
        int n = shown.size();
        countView.setText(empty ? "" : n == 1 ? "1 app" : n + " apps");
    }

    private boolean isForeground(AppEntry e) {
        return e.resumed || (System.currentTimeMillis() - e.lastEvent) < HOT_MS;
    }

    private String currentPkg() {
        int i = carousel == null ? -1 : carousel.getSelected();
        if (i >= 0 && i < shown.size()) {
            return shown.get(i).pkg;
        }
        return null;
    }

    /** 用 UsageEvents 还原「最近前台 → 是否又退到后台」。 */
    private List<AppEntry> queryActive() {
        List<AppEntry> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return out;
        }

        long now = System.currentTimeMillis();
        Map<String, long[]> seen = new HashMap<>(); // pkg -> {lastResumed, lastEvent, resumed}
        try {
            UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
            if (events != null) {
                UsageEvents.Event ev = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    String pkg = ev.getPackageName();
                    if (pkg == null || pkg.equals(getPackageName())) {
                        continue;
                    }
                    long ts = ev.getTimeStamp();
                    int type = ev.getEventType();
                    long[] cur = seen.get(pkg);
                    if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        long active = cur == null ? ts : Math.max(cur[0], ts);
                        seen.put(pkg, new long[]{active, ts, 1});
                    } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                        if (cur != null && cur[1] <= ts) {
                            seen.put(pkg, new long[]{cur[0], ts, 0});
                        } else {
                            seen.put(pkg, new long[]{ts, ts, 0});
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 部分 ROM 会拦截，返回空列表
        }

        PackageManager pm = getPackageManager();
        for (Map.Entry<String, long[]> en : seen.entrySet()) {
            String pkg = en.getKey();
            if (pkg.equals(launcherPkg) || pkg.equals(SHIZUKU_PKG)) {
                continue;
            }
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(pkg, 0);
            } catch (Exception ex) {
                continue;
            }
            // 只保留能从桌面启动的应用
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                continue;
            }

            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = pm.getApplicationLabel(ai).toString();
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(ai);
            } catch (Exception ex) {
                icon = null;
            }
            e.icon = icon != null ? icon : pm.getDefaultActivityIcon();
            e.lastActive = en.getValue()[0];
            e.lastEvent = en.getValue()[1];
            e.resumed = en.getValue()[2] == 1;
            out.add(e);
        }

        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (a.resumed != b.resumed) {
                    return a.resumed ? -1 : 1;
                }
                return Long.compare(b.lastActive, a.lastActive);
            }
        });
        return out;
    }

    private String resolveLauncher() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) {
                return ri.activityInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------- 动作

    private void openCurrent() {
        openApp(carousel.getSelected());
    }

    private void openApp(int index) {
        if (index < 0 || index >= shown.size()) {
            return;
        }
        AppEntry e = shown.get(index);
        Intent i = getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (i == null) {
            toast("Can't open " + e.label);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            toast("Can't open " + e.label);
        }
    }

    private void closeCurrent() {
        // 「上滑关闭」和 X 键共用同一套退场动效：先滑出去淡出，动画结束后再真正结束应用
        if (carousel == null || !carousel.dismissCurrent()) {
            return;
        }
    }

    /** 卡片上滑 / 退场动画结束后真正结束应用。 */
    private void closeIndex(int index) {
        if (index < 0 || index >= shown.size()) {
            return;
        }
        AppEntry e = shown.get(index);
        boolean ok;
        if (mode == MODE_TASKS) {
            // Shizuku 走 shell 的 force-stop，前台应用也能结束
            ok = TaskBridge.stop(e.pkg, e.taskId);
        } else {
            ok = true;
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                am.killBackgroundProcesses(e.pkg);
            } catch (Exception ex) {
                ok = false;
            }
        }

        all.remove(e);
        shown.remove(index);

        List<View> cards = new ArrayList<>();
        for (AppEntry x : shown) {
            cards.add(buildCard(x));
        }
        int next = Math.min(index, Math.max(0, shown.size() - 1));
        // 用带重排动画的版本：剩下的卡片从旧位置滑到新位置，不会「闪」一下跳过去
        carousel.setCardsAnimated(cards, next, index);

        boolean empty = shown.isEmpty();
        carousel.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            updateEmpty();
        }
        updateHeader();
        updateDots();
        toast((ok ? "Closed · " : "Close failed · ") + e.label);
        // 稍后重新读一次任务栈，保证列表和系统一致
        ui.postDelayed(this::reload, 800);
    }

    private void toggleMode() {
        showBackground = !showBackground;
        buildCards();
        toast(showBackground ? "Showing background apps" : "Showing foreground only");
    }

    /**
     * START：熄屏休眠，并顺手结束自己。
     *
     * 普通应用没有 DEVICE_POWER 权限，熄屏只能借 Shizuku 用户服务的 shell 身份注入
     * KEYCODE_SLEEP。注入失败（Shizuku 没连上）时不假装成功——提示一句并留在界面上，
     * 否则用户会看到一个「按了没反应」的黑屏假象。
     */
    private void sleepAndExit() {
        keepAlive = false;
        // 熄屏必须在 finish 之前落地：finish 之后进程随时可能被回收，binder 事务会丢
        final boolean[] ok = {false};
        Thread t = new Thread(() -> ok[0] = TaskBridge.sleepScreen(), "sleep-screen");
        t.start();
        try {
            t.join(600);
        } catch (InterruptedException ignored) {
        }
        if (!ok[0]) {
            toast("Connect Shizuku first to turn the screen off");
            return;
        }
        try {
            finishAndRemoveTask();
        } catch (Throwable ignored) {
        }
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            toast("Enable it in Settings → Apps → Special access");
        }
    }

    private void openAppInfo(String pkg) {
        try {
            // NEW_TASK：系统设置要落在自己的任务里，否则会和 TaskMan 共用任务，
            // 离开时 finishAndRemoveTask() 会把设置页一起带走
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            toast("Can't open Settings");
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) {
            return false;
        }
        int mode;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            }
        } catch (Exception ex) {
            return false;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // ------------------------------------------------------------- 手柄

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = e.getRepeatCount() == 0;

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && (first || e.getRepeatCount() % 3 == 0)) {
                    carousel.move(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && (first || e.getRepeatCount() % 3 == 0)) {
                    carousel.move(1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_A:          // 部分手柄会以键盘模式上报字母键
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && first) {
                    openCurrent();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_X:
                if (down && first) {
                    closeCurrent();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_Y:
                if (down && first) {
                    toggleMode();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:   // 108，Odin/Xbox 手柄的 START
                if (down && first) {
                    sleepAndExit();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // --------------------------------------------------------- 轮播回调

    @Override
    public void onSelected(int index) {
        // 卡片高亮由 CarouselView 自己画，这里只更新底部的位置指示器
        updateDots();
    }

    @Override
    public void onActivate(int index) {
        openApp(index);
    }

    /** 上滑关闭：CarouselView 的退场动画播完后回调，这里才是真正的 force-stop / remove task。 */
    @Override
    public void onDismiss(int index) {
        closeIndex(index);
    }

    @Override
    public void onLongPress(int index) {
        if (index < 0 || index >= shown.size()) {
            return;
        }
        openAppInfo(shown.get(index).pkg);
    }

    // ------------------------------------------------------------- 卡片

    private View buildCard(final AppEntry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(15));
        GradientDrawable bg = round(t.card, Theme.RADIUS_CARD);
        bg.setStroke(Math.max(1, dp(1)), t.cardStroke);
        card.setBackground(bg);
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= 26) {
            card.setDefaultFocusHighlightEnabled(false);
        }
        // 卡片直接浮在渐变背景上，靠投影分层；暗色下投影很淡，所以卡片本身比背景亮一档
        card.setElevation(t.dark ? dp(14) : dp(10));
        if (Build.VERSION.SDK_INT >= 28) {
            card.setOutlineAmbientShadowColor(t.shadow);
            card.setOutlineSpotShadowColor(t.shadow);
        }

        // 图标区：系统任务缩略图模糊后铺底，上面压应用图标（拿不到截图就只剩渐变块）
        FrameLayout art = new FrameLayout(this);
        GradientDrawable artBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{t.tileTop, t.tileBottom});
        artBg.setCornerRadius(dp(Theme.RADIUS_ART));
        art.setBackground(artBg);
        // 背景是圆角矩形 → 用它当 outline，把里面的缩略图一起裁圆，否则会露出方角
        art.setClipToOutline(true);

        ImageView shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 压一点不透明度让卡片主题渐变透上来；暗色下再压狠一些，否则亮色截图会把卡片冲淡
        shot.setAlpha(t.dark ? 0.62f : 0.82f);
        shot.setVisibility(View.GONE);
        art.addView(shot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(e.icon);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                dp(96), dp(96), Gravity.CENTER);
        art.addView(icon, ilp);
        card.addView(art, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadArt(e, shot);

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextColor(t.text);
        name.setTextSize(21);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(14);
        card.addView(name, nlp);

        TextView pkg = new TextView(this);
        pkg.setText(e.pkg);
        pkg.setTextColor(t.pkg);
        pkg.setTextSize(10);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(2);
        card.addView(pkg, klp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(e.resumed ? t.green : t.dotOff);
        dot.setBackground(dotBg);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setPadding(dp(7), 0, 0, 0);
        // 卡片上只写时间（前台那张写 In foreground），不再区分 “Task / Background” 这种词
        if (e.resumed) {
            status.setText("In foreground");
            status.setTextColor(t.green);
        } else if (e.lastActive > 0) {
            status.setText(rel(e.lastActive));
            status.setTextColor(t.sub);
        } else {
            status.setVisibility(View.GONE);
        }
        statusRow.addView(status);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        card.addView(statusRow, slp);

        // 长按由 CarouselView 统一识别（卡片自身不接手势），这里不再挂 listener
        return card;
    }

    /**
     * 异步取任务缩略图 → 缩小模糊 → 当卡片图标区的底图。
     * 拿不到（Shizuku 没连 / 系统没存 snapshot / API < 29）就什么都不做，卡片保持渐变块。
     */
    private void loadArt(final AppEntry e, final ImageView shot) {
        if (e.pkg == null || e.taskId <= 0) {
            return;
        }
        Bitmap cached = artCache.get(e.pkg);
        if (cached != null) {
            shot.setImageBitmap(cached);
            shot.setAlpha(t.dark ? 0.62f : 0.82f);
            shot.setVisibility(View.VISIBLE);
            return;
        }
        shot.setAlpha(0f);
        shot.setTag(e.taskId);
        new Thread(() -> {
            byte[] jpg = TaskBridge.snapshot(e.taskId);
            if (jpg == null) {
                return;
            }
            Bitmap raw;
            try {
                raw = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            } catch (Throwable t) {
                return;
            }
            if (raw == null) {
                return;
            }
            Bitmap blurred = null;
            try {
                blurred = blur(raw);
            } catch (Throwable ignored) {
            }
            if (blurred == null) {
                raw.recycle();
                return;
            }
            if (blurred != raw) {
                raw.recycle();
            }
            final Bitmap art = blurred;
            ui.post(() -> {
                // 卡片可能已经被重建（翻页/主题切换），tag 不一致就别往上贴了
                if (!Integer.valueOf(e.taskId).equals(shot.getTag())) {
                    return;
                }
                artCache.put(e.pkg, art);
                shot.setImageBitmap(art);
                shot.setVisibility(View.VISIBLE);
                shot.animate().alpha(t.dark ? 0.62f : 0.82f).setDuration(220).start();
            });
        }, "art").start();
    }

    /**
     * 缩小 + 盒式模糊。直接对 480px 宽的图做高斯太慢，先缩到 160px，模糊完再让 ImageView
     * 放大（CENTER_CROP 自带双线性过滤），视觉上够糊且几乎不耗 CPU。
     */
    private static Bitmap blur(Bitmap src) {
        int w = Math.min(192, src.getWidth());
        if (w < 8) {
            return null;
        }
        int h = Math.max(1, src.getHeight() * w / src.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        boxBlur(px, w, h, Math.max(2, w / 32), 2);
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxBlur(int[] px, int w, int h, int r, int passes) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            blurLine(px, tmp, w, h, r, true);
            blurLine(tmp, px, w, h, r, false);
        }
    }

    /** 一维盒式模糊；horizontal=true 横着扫，否则竖着扫。 */
    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int o = 0; o < outer; o++) {
            int base = horizontal ? o * w : o;
            for (int i = 0; i < inner; i++) {
                int rr = 0, gg = 0, bb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = i + k;
                    if (j < 0) {
                        j = 0;
                    } else if (j >= inner) {
                        j = inner - 1;
                    }
                    int c = in[base + j * step];
                    rr += (c >> 16) & 0xFF;
                    gg += (c >> 8) & 0xFF;
                    bb += c & 0xFF;
                    n++;
                }
                out[base + i * step] = 0xFF000000
                        | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
        }
    }

    /** Relative time in English (the UI does not follow the system locale). */
    private String rel(long ts) {
        long delta = Math.max(0L, System.currentTimeMillis() - ts);
        if (delta < 45_000L) {
            return "just now";
        }
        long min = delta / 60_000L;
        if (min < 60) {
            return min + " min ago";
        }
        long hours = min / 60;
        if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    // ------------------------------------------------------------- 工具

    private TextView pill(String text, int textColor, int bgColor,
                          int textSp, int padH, int padV) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(textSp);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        t.setBackground(round(bgColor, 14));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private final Runnable hideBanner = new Runnable() {
        @Override
        public void run() {
            if (banner == null) {
                return;
            }
            banner.animate().alpha(0f).setDuration(180).withEndAction(
                    () -> banner.setVisibility(View.GONE)).start();
        }
    };

    /** 应用内提示，避免系统 Toast 的大白条盖住卡片。 */
    private void toast(String msg) {
        if (banner == null) {
            return;
        }
        banner.animate().cancel();
        banner.setText(msg);
        banner.setAlpha(0f);
        banner.setVisibility(View.VISIBLE);
        banner.animate().alpha(1f).setDuration(120).start();
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 1600);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
