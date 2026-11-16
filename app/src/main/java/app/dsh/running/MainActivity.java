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
import android.text.format.DateUtils;
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
 * 触屏：滑动切换，点中间卡片打开，点两侧卡片滑过去。
 */
public class MainActivity extends Activity implements CarouselView.Listener {

    private static final long WINDOW_MS = 6L * 60L * 60L * 1000L;   // 看多远的使用记录
    private static final long HOT_MS = 3L * 60L * 1000L;            // 刚退到后台多久内仍算前台

    private static final int C_BG = 0xFF0B0E13;
    private static final int C_CARD = 0xFF141A23;
    private static final int C_CARD_STROKE = 0xFF232C3A;
    private static final int C_TEXT = 0xFFEDF1F7;
    private static final int C_SUB = 0xFF8B95A7;
    private static final int C_GREEN = 0xFF3DDC84;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<AppEntry> all = new ArrayList<>();
    private final ArrayList<AppEntry> shown = new ArrayList<>();

    private CarouselView carousel;
    private LinearLayout headerBar;
    private LinearLayout footerBar;
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
            tv.setText("启动出错了，把这段发给我：\n\n" + trace);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l = 0, t = 0, r = 0, b = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            // 舞台必须满宽，否则左右 cutout 不等会把中间卡片挤偏
            v.setPadding(0, t + dp(8), 0, b + dp(4));
            if (headerBar != null) {
                headerBar.setPadding(l + dp(24), dp(6), r + dp(24), 0);
            }
            if (footerBar != null) {
                footerBar.setPadding(l + dp(24), dp(8), r + dp(24), dp(2));
            }
            return insets;
        });

        // 顶部：标题 + 模式胶囊 + 数量
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("TaskMan");
        title.setTextColor(C_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title);

        View headSpacer = new View(this);
        header.addView(headSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        countView = new TextView(this);
        countView.setTextColor(C_SUB);
        countView.setTextSize(12);
        header.addView(countView);
        headerBar = header;
        root.addView(header);

        // 中间：轮播
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        carousel = new CarouselView(this);
        carousel.setListener(this);
        stage.addView(carousel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        banner = new TextView(this);
        banner.setTextColor(C_TEXT);
        banner.setTextSize(12);
        banner.setPadding(dp(18), dp(9), dp(18), dp(9));
        banner.setBackground(round(0xF01B222D, 20));
        banner.setAlpha(0f);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(6);
        stage.addView(banner, blp);

        shizukuHint = buildShizukuHint();
        shizukuHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(6);
        stage.addView(shizukuHint, hlp);

        emptyView = buildEmpty();
        emptyView.setVisibility(View.GONE);
        stage.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 底部：按键说明
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(8), 0, dp(2));
        footer.addView(keyCap("A"));
        footer.addView(hint("打开"));
        footer.addView(keyCap("X"));
        footer.addView(hint("关闭"));
        footer.addView(keyCap("START"));
        footer.addView(hint("熄屏"));
        footerBar = footer;
        root.addView(footer);
        return root;
    }

    private View buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(10), dp(24), dp(10));

        emptyTitle = new TextView(this);
        emptyTitle.setText("无任务");
        emptyTitle.setTextColor(C_SUB);
        emptyTitle.setTextSize(16);
        emptyTitle.setGravity(Gravity.CENTER);
        box.addView(emptyTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptySub = new TextView(this);
        emptySub.setTextColor(C_SUB);
        emptySub.setTextSize(11);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(0, dp(6), 0, 0);
        emptySub.setVisibility(View.GONE);
        box.addView(emptySub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        permBtn = pill("去开启使用情况访问", C_GREEN, 0x1F3DDC84, 12, 16, 8);
        permBtn.setVisibility(View.GONE);
        permBtn.setOnClickListener(v -> openUsageAccess());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(14);
        box.addView(permBtn, plp);

        modeBtn = pill("显示后台应用 (Y)", C_TEXT, 0xFF222A36, 12, 16, 8);
        modeBtn.setVisibility(View.GONE);
        modeBtn.setOnClickListener(v -> toggleMode());
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp2.topMargin = dp(10);
        box.addView(modeBtn, mlp2);
        modeBtn.setVisibility(View.GONE);
        return box;
    }

    private TextView keyCap(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(C_TEXT);
        v.setTextSize(10);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(20));
        v.setPadding(dp(6), dp(3), dp(6), dp(3));
        v.setBackground(round(0xFF222A36, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView hint(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(C_SUB);
        v.setTextSize(11);
        v.setPadding(dp(5), 0, 0, 0);
        return v;
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
            sb.append(e.pkg).append('\n');
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
        for (String pkg : pkgs.split("\n")) {
            if (pkg.isEmpty()) {
                continue;
            }
            AppEntry e = entryFor(pm, pkg);
            if (e != null) {
                e.fromTask = true;
                e.cached = true;
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
    }

    private View buildShizukuHint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable bg = round(0xF0241A17, 18);
        bg.setStroke(Math.max(1, dp(1)), 0x66FF8A65);
        box.setBackground(bg);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFFFF8A65);
        dot.setBackground(dotBg);
        box.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        shizukuHintText = new TextView(this);
        shizukuHintText.setTextColor(0xFFFFC9B8);
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
            shizukuHintText.setText("Shizuku 未运行 · 点此打开");
        } else {
            shizukuHintText.setText("未安装 Shizuku · 任务列表不可用");
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
                toast("没有找到 Shizuku");
            }
        } catch (Throwable t) {
            toast("打不开 Shizuku");
        }
    }

    private void updateEmpty() {
        boolean granted = hasUsageAccess();
        permBtn.setVisibility(mode == MODE_FALLBACK && !granted ? View.VISIBLE : View.GONE);
        modeBtn.setVisibility(View.GONE);
        emptySub.setVisibility(View.GONE);
        if (mode == MODE_CONNECTING) {
            emptyTitle.setText("正在连接…");
            emptyTitle.setTextColor(C_SUB);
            emptyTitle.setTextSize(16);
        } else if (mode == MODE_FALLBACK && !granted) {
            emptyTitle.setText("需要「使用情况访问」权限");
            emptyTitle.setTextColor(C_TEXT);
            emptyTitle.setTextSize(15);
        } else {
            emptyTitle.setText("无任务");
            emptyTitle.setTextColor(C_SUB);
            emptyTitle.setTextSize(16);
        }
    }

    private void updateHeader() {
        countView.setText(shown.size() == 0 ? "" : shown.size() + " 个应用");
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
            toast("打不开 " + e.label);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            toast("打不开 " + e.label);
        }
    }

    private void closeCurrent() {
        int index = carousel.getSelected();
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
        carousel.setCards(cards, next);

        boolean empty = shown.isEmpty();
        carousel.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            updateEmpty();
        }
        updateHeader();
        toast((ok ? "已结束 · " : "结束失败 · ") + e.label);
        // 稍后重新读一次任务栈，保证列表和系统一致
        ui.postDelayed(this::reload, 800);
    }

    private void toggleMode() {
        showBackground = !showBackground;
        buildCards();
        toast(showBackground ? "显示后台应用" : "只显示前台应用");
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
            toast("熄屏需要先连上 Shizuku");
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
            toast("请到 设置 → 应用 → 特殊权限 里开启");
        }
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (ActivityNotFoundException ignored) {
            toast("打不开系统设置");
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
        // 选中变化时不需要重绘整页，卡片高亮由 CarouselView 自己画
    }

    @Override
    public void onActivate(int index) {
        openApp(index);
    }

    // ------------------------------------------------------------- 卡片

    private View buildCard(final AppEntry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        GradientDrawable bg = round(C_CARD, 20);
        bg.setStroke(Math.max(1, dp(1)), C_CARD_STROKE);
        card.setBackground(bg);
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= 26) {
            card.setDefaultFocusHighlightEnabled(false);
        }

        card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(e.icon);
        card.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextColor(C_TEXT);
        name.setTextSize(21);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(18);
        card.addView(name, nlp);

        TextView pkg = new TextView(this);
        pkg.setText(e.pkg);
        pkg.setTextColor(0xFF6F7A8C);
        pkg.setTextSize(10);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(pkg);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(e.resumed ? C_GREEN : 0xFF576273);
        dot.setBackground(dotBg);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setPadding(dp(7), 0, 0, 0);
        if (e.resumed) {
            status.setText("前台运行");
            status.setTextColor(C_GREEN);
        } else if (e.cached) {
            status.setText("任务");
            status.setTextColor(C_SUB);
        } else if (e.fromTask) {
            status.setText("任务 · " + rel(e.lastActive));
            status.setTextColor(C_SUB);
        } else {
            status.setText("后台 · " + rel(e.lastActive));
            status.setTextColor(C_SUB);
        }
        statusRow.addView(status);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(18);
        card.addView(statusRow, slp);

        card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        card.setOnLongClickListener(v -> {
            openAppInfo(e.pkg);
            return true;
        });
        return card;
    }

    private String rel(long ts) {
        long delta = System.currentTimeMillis() - ts;
        if (delta < 60_000L) {
            return "刚刚";
        }
        return DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
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
