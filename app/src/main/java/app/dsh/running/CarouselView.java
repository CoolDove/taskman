package app.dsh.running;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.util.List;

/**
 * 横向卡片轮播：中间一张永远居中、全尺寸、高亮；左右各露出一半。
 *
 * step = 宽度的一半 => 相邻卡片露出的部分正好是卡片宽度的一半。
 * 卡片数量 >= 3 时循环：第一张的左边永远是最后一张，不会出现空位。
 * 滚动用 ValueAnimator + PathInterpolator（ease-out）驱动，拖拽后自动吸附。
 *
 * 触屏手势全部在这里判定（卡片自身不接手势）：
 *   横滑 —— 跟手移动，松手按位移 + 甩动速度吸附；
 *   上滑 —— 抓起居中卡片往上拖，过半屏或快速上甩就飞出去并回调 onDismiss；
 *   点按 —— 中间打开、两侧滑过去；长按 —— 回调 onLongPress（进应用详情）。
 * 键盘 X 关闭也走同一套上滑淡出动效（{@link #dismissCurrent()}）。
 */
public class CarouselView extends ViewGroup {

    public interface Listener {
        void onSelected(int index);
        void onActivate(int index);
        void onDismiss(int index);
        void onLongPress(int index);
    }

    private static final float CARD_RATIO = 0.42f;   // 卡片宽 / 屏宽
    private static final float SCALE_MIN = 0.86f;   // 侧边卡片的缩放

    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Theme theme;

    /** 外发光三层：(描边宽度dp, 不透明度0..255)，从外到内。 */
    private static final float[] GLOW_WIDTH = {16f, 10f, 5f};
    private static final int[] GLOW_ALPHA = {0x22, 0x38, 0x55};

    private int cardW;
    private int cardH;
    private int step;
    private float offset;
    private int count;
    private ValueAnimator anim;
    private Listener listener;
    private int lastSelected = -1;

    // ---------------------------------------------------------------- 手势状态
    private static final int G_NONE = 0;   // 还没超过 touch slop
    private static final int G_H = 1;      // 横滑
    private static final int G_V = 2;      // 上滑关闭
    private static final int G_BLOCKED = 3; // 退场动画还没结束，忽略这一轮触摸

    private float downX;
    private float downY;
    private float downOffset;
    private int gesture = G_NONE;
    private boolean longPressed;
    private VelocityTracker vt;

    /** 正在被上滑的那张卡片（退场动画期间保持有效）。 */
    private int swipeIndex = -1;
    private float swipeDy;          // 上滑位移，<= 0
    private float swipeAlpha = 1f;
    private float swipeScale = 1f;
    private ValueAnimator swipeAnim;
    private boolean dismissAnimating;
    /** 关掉一张卡片后的重排动画（剩下的卡片滑到新位置）。 */
    private ValueAnimator reflowAnim;
    /** 重排进度 0..1：焦点环跟着淡入，避免「高亮」在删掉的瞬间跳到旁边那张身上。 */
    private float reflowAlpha = 1f;
    /** 绘制顺序：离中心越远的越先画，居中那张最后画（压在上面）。 */
    private int[] drawOrder;

    /** 长按判定：500ms 内没划动才算长按。 */
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (gesture != G_NONE || count <= 0) {
                return;
            }
            longPressed = true;
            int idx = indexAt(downX);
            if (idx >= 0 && listener != null) {
                listener.onLongPress(idx);
            }
        }
    };

    public CarouselView(Context context) {
        this(context, null);
    }

    public CarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3f));
        glowPaint.setStyle(Paint.Style.STROKE);
        setClipChildren(true);
        setClickable(true);
        // 允许自定义画序：让居中那张永远画在最上面，其它卡片从它后面穿过去
        setChildrenDrawingOrderEnabled(true);
        // 别让系统画默认的灰白焦点蒙版：按键都在 Activity 层处理，不需要 View 焦点
        setFocusable(false);
        setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
    }

    /** 换主题：焦点环颜色跟着变。 */
    public void setTheme(Theme t) {
        theme = t;
        invalidate();
    }

    private float alphaMin() {
        return theme != null && !theme.dark ? 0.85f : 0.55f;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int count() {
        return count;
    }

    /** 当前居中的卡片下标（已取模）。 */
    public int getSelected() {
        if (count <= 0) {
            return 0;
        }
        return wrap(Math.round(offset));
    }

    /** 换一批卡片；initial 为初始居中位置。 */
    public void setCards(List<View> cards, int initial) {
        stopAnim();
        cancelSwipeAnim();
        cancelReflow();
        dismissAnimating = false;
        swipeIndex = -1;
        swipeDy = 0f;
        swipeAlpha = 1f;
        swipeScale = 1f;
        removeAllViews();
        for (View v : cards) {
            addView(v);
        }
        count = cards.size();
        offset = count > 0 ? wrap(initial) : 0;
        lastSelected = getSelected();
        requestLayout();
        relayout();
    }

    /**
    /**
     * 关掉一张卡片：**就地把它从容器里抽走**，剩下的卡片用动画滑到新位置。
     *
     * 之前的做法是整批重建视图（removeAllViews + addView），有两个副作用：
     *   1) 新建 ImageView / TextView 发生在退场动画结束的那一帧，容易掉帧；
     *   2) 新视图要先摆回旧位置再补间，中间会有一帧露出「最终位置」，看着像闪一下。
     * 现在视图全部复用，只是 removeViewAt + 补间，没有任何重建。
     */
    public void removeCard(int index) {
        int n = getChildCount();
        if (index < 0 || index >= n) {
            return;
        }
        stopAnim();
        cancelSwipeAnim();
        cancelReflow();
        dismissAnimating = false;
        swipeIndex = -1;
        swipeDy = 0f;
        swipeAlpha = 1f;
        swipeScale = 1f;

        // 记录抽走之前每张卡的实际位置（重排动画的起点）
        float[] ox = new float[n], oy = new float[n], os = new float[n], oa = new float[n];
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            ox[i] = c.getX();
            oy[i] = c.getY();
            os[i] = c.getScaleX();
            oa[i] = c.getAlpha();
        }

        removeViewAt(index);
        count = getChildCount();
        if (count > 0) {
            // 被删的那张后面的卡片整体前移一位，所以 offset 仍指向 index（越界则夹到最后一张）
            offset = wrap(Math.min(index, count - 1));
        } else {
            offset = 0;
        }
        lastSelected = getSelected();
        if (count == 0 || cardW == 0) {
            invalidate();
            return;
        }

        int m = count;
        float[] sx = new float[m], sy = new float[m], ss = new float[m], sa = new float[m];
        float[] fx = new float[m], fy = new float[m], fs = new float[m], fa = new float[m];
        float[] t = new float[4];
        for (int i = 0; i < m; i++) {
            int oi = i < index ? i : i + 1;
            sx[i] = ox[oi];
            sy[i] = oy[oi];
            ss[i] = os[oi];
            sa[i] = oa[oi];
            layoutOf(i, t);
            fx[i] = t[0];
            fy[i] = t[1];
            fs[i] = t[2];
            fa[i] = t[3];
        }
        startReflow(sx, sy, ss, sa, fx, fy, fs, fa);
    }

    /** 把每张卡片从 (sx,sy,ss,sa) 补间到 (fx,fy,fs,fa)，并把焦点环淡入。 */
    private void startReflow(float[] sx, float[] sy, float[] ss, float[] sa,
                             float[] fx, float[] fy, float[] fs, float[] fa) {
        final int n = getChildCount();
        if (n == 0 || fx.length < n) {
            return;
        }
        // 长距离位移的卡片会从居中那张后面穿过去：靠 getChildDrawingOrder 让它先画（压在下面），
        // 所以这里不需要淡出淡入 —— 那样反而会闪
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            c.setPivotX(cardW / 2f);
            c.setPivotY(cardH / 2f);
            c.setX(sx[i]);
            c.setY(sy[i]);
            c.setScaleX(ss[i]);
            c.setScaleY(ss[i]);
            c.setAlpha(sa[i]);
        }
        updateDrawOrder();
        // 焦点环从 0 淡入，否则「谁被高亮」会在删掉的瞬间突然跳到旁边那张身上
        reflowAlpha = 0f;
        invalidate();

        reflowAnim = ValueAnimator.ofFloat(0f, 1f);
        reflowAnim.setDuration(320);
        reflowAnim.setInterpolator(ease);
        reflowAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            // 焦点环晚点出场：卡片快到位时才开始亮，避免环「悬」在半路上的卡片外面
            reflowAlpha = Math.max(0f, Math.min(1f, (f - 0.55f) / 0.45f));
            for (int i = 0; i < getChildCount() && i < n; i++) {
                View c = getChildAt(i);
                c.setX(sx[i] + (fx[i] - sx[i]) * f);
                c.setY(sy[i] + (fy[i] - sy[i]) * f);
                float s = ss[i] + (fs[i] - ss[i]) * f;
                c.setScaleX(s);
                c.setScaleY(s);
                c.setAlpha(sa[i] + (fa[i] - sa[i]) * f);
            }
            // 焦点环画在 dispatchDraw 里，要跟着刷新
            invalidate();
        });
        reflowAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                reflowAnim = null;
                reflowAlpha = 1f;
                relayout();
            }
        });
        reflowAnim.start();
    }

    /** 是否有卡片正在做上滑退场（此时别重铺卡片，否则动画会被抽走）。 */
    public boolean isDismissing() {
        return dismissAnimating;
    }

    /** 无动画定位。 */
    public void select(int index) {
        stopAnim();
        offset = count > 0 ? wrap(index) : 0;
        lastSelected = getSelected();
        relayout();
    }

    /** 带 easing 的滚动。 */
    public void smoothTo(int index) {
        if (count <= 0) {
            return;
        }
        stopAnim();
        final float to;
        if (loops()) {
            to = index;
        } else {
            to = Math.max(0, Math.min(count - 1, index));
        }
        if (Math.abs(to - offset) < 0.0005f) {
            offset = to;
            relayout();
            notifySelected();
            return;
        }
        anim = ValueAnimator.ofFloat(offset, to);
        // 距离越远滚得越久一点，但别拖沓
        float dist = Math.abs(to - offset);
        anim.setDuration((long) Math.min(420f, 190f + 110f * dist));
        anim.setInterpolator(ease);
        anim.addUpdateListener(a -> {
            offset = (Float) a.getAnimatedValue();
            relayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                offset = to;
                if (loops()) {
                    // 取模不改变视觉位置，只是防止 offset 无限增大
                    offset = wrap(Math.round(offset)) + (offset - Math.round(offset));
                }
                relayout();
                notifySelected();
            }
        });
        anim.start();
    }

    public void move(int delta) {
        smoothTo(Math.round(offset) + delta);
    }

    /**
     * 把当前居中的卡片（带 X 键 / 外部调用）用上滑淡出的动效甩出去，
     * 动画结束后回调 {@link Listener#onDismiss(int)}，由界面负责真正结束应用。
     *
     * @return 是否真的启动了动画（没有卡片、或上一次退场还没结束则返回 false）
     */
    public boolean dismissCurrent() {
        if (count <= 0 || swipeIndex >= 0 || dismissAnimating || cardH == 0) {
            return false;
        }
        stopAnim();
        swipeIndex = getSelected();
        swipeDy = 0f;
        swipeAlpha = 1f;
        swipeScale = 1f;
        startSwipeOut();
        return true;
    }

    private boolean loops() {
        return count >= 3;
    }

    private int wrap(int index) {
        if (count <= 0) {
            return 0;
        }
        int m = index % count;
        return m < 0 ? m + count : m;
    }

    private void stopAnim() {
        if (anim != null) {
            anim.removeAllListeners();
            anim.removeAllUpdateListeners();
            anim.cancel();
            anim = null;
        }
    }

    private void cancelSwipeAnim() {
        if (swipeAnim != null) {
            ValueAnimator a = swipeAnim;
            swipeAnim = null;
            a.removeAllListeners();
            a.removeAllUpdateListeners();
            a.cancel();
        }
    }

    /** 结束／取消重排动画（立刻落到最终位置）。 */
    private void cancelReflow() {
        reflowAlpha = 1f;
        if (reflowAnim != null) {
            ValueAnimator a = reflowAnim;
            reflowAnim = null;
            a.removeAllListeners();
            a.removeAllUpdateListeners();
            a.cancel();
        }
    }

    /** 结束／取消上滑手势，卡片回到原位。 */
    private void resetSwipe() {
        swipeIndex = -1;
        swipeDy = 0f;
        swipeAlpha = 1f;
        swipeScale = 1f;
        relayout();
    }

    // ------------------------------------------------------------ 上滑退场

    /** 手指拖动时卡片跟手上移，过半后加重阻尼；同时逐渐缩小、变淡。 */
    private void dragSwipe(float dy) {
        float up = Math.min(0f, dy);
        float soft = cardH * 0.95f;
        if (-up > soft) {
            up = -soft - (-up - soft) * 0.25f;
        }
        swipeDy = up;
        float p = Math.min(1f, -swipeDy / (cardH * 0.85f));
        swipeAlpha = 1f - 0.72f * p;
        swipeScale = 1f - 0.07f * p;
        relayout();
    }

    /** 没到阈值：弹回去。 */
    private void springBack() {
        final float fromY = swipeDy;
        final float fromA = swipeAlpha;
        final float fromS = swipeScale;
        cancelSwipeAnim();
        swipeAnim = ValueAnimator.ofFloat(0f, 1f);
        swipeAnim.setDuration(210);
        swipeAnim.setInterpolator(ease);
        swipeAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            swipeDy = fromY * (1f - f);
            swipeAlpha = fromA + (1f - fromA) * f;
            swipeScale = fromS + (1f - fromS) * f;
            relayout();
        });
        swipeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                swipeAnim = null;
                resetSwipe();
            }
        });
        swipeAnim.start();
    }

    /** 过阈值（或按了 X）：加速上滑 + 淡出，结束后才回调关闭。 */
    private void startSwipeOut() {
        final int idx = swipeIndex;
        final float fromY = swipeDy;
        final float fromA = swipeAlpha;
        final float fromS = swipeScale;
        // 至少飞到卡片上边缘之外，已经拖得很远就少飞一点
        final float dist = Math.max(cardH * 0.6f, -fromY + dp(56));
        float p = Math.min(1f, -fromY / (cardH * 0.85f));
        long dur = (long) (140 + 120 * (1f - p));

        dismissAnimating = true;
        cancelSwipeAnim();
        swipeAnim = ValueAnimator.ofFloat(0f, 1f);
        swipeAnim.setDuration(dur);
        swipeAnim.setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f));
        swipeAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            swipeDy = fromY - dist * f;
            swipeAlpha = fromA * (1f - f);
            swipeScale = fromS - 0.04f * f;
            relayout();
        });
        swipeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                swipeAnim = null;
                dismissAnimating = false;
                // 先把视图复位，再通知出去：界面重新铺卡片时不会看到残留的旧卡
                resetSwipe();
                if (listener != null) {
                    listener.onDismiss(idx);
                }
            }
        });
        swipeAnim.start();
    }

    private void notifySelected() {
        int index = getSelected();
        if (index != lastSelected) {
            lastSelected = index;
            if (listener != null) {
                listener.onSelected(index);
            }
        }
    }

    /** 卡片 i 相对 offset 的有符号距离（循环时取最短方向）。 */
    private float distance(int i) {
        float d = i - offset;
        if (!loops()) {
            return d;
        }
        float half = count / 2f;
        while (d > half) {
            d -= count;
        }
        while (d < -half) {
            d += count;
        }
        return d;
    }

    // ------------------------------------------------------------ 布局

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        cardW = Math.round(w * CARD_RATIO);
        step = Math.max(1, w / 2);                  // 相邻卡片中心距 = 半屏
        cardH = Math.min(h, Math.round(cardW * 0.80f));

        int cw = MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(0, 0, cardW, cardH);
            c.setPivotX(cardW / 2f);
            c.setPivotY(cardH / 2f);
        }
        // 重排动画进行中不能被布局「拉回终点」：removeViewAt 会 requestLayout，
        // 这一帧如果照常 relayout()，会先闪一帧「最终位置」再回到起点做动画
        if (reflowAnim == null) {
            relayout();
        }
    }

    /** 卡片 i 在静止状态下的 x / y / 缩放 / 透明度，写进 out[4]。 */
    private void layoutOf(int i, float[] out) {
        int w = getWidth();
        int h = getHeight();
        float cx = (w - cardW) / 2f;
        float cy = (h - cardH) / 2f;
        float d = distance(i);
        float f = Math.min(1f, Math.abs(d));
        out[0] = cx + d * step;
        out[1] = cy;
        out[2] = 1f - (1f - SCALE_MIN) * f;
        out[3] = 1f - (1f - alphaMin()) * f;
    }

    private void relayout() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || cardW == 0) {
            return;
        }
        float cx = (w - cardW) / 2f;
        float cy = (h - cardH) / 2f;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            float d = distance(i);
            float f = Math.min(1f, Math.abs(d));
            float s = 1f - (1f - SCALE_MIN) * f;
            float a = 1f - (1f - alphaMin()) * f;
            float y = cy;
            if (i == swipeIndex) {
                // 上滑手势 / 退场动画：卡片整体上移、缩小、变淡
                s *= swipeScale;
                a *= swipeAlpha;
                y = cy + swipeDy;
            }
            c.setX(cx + d * step);
            c.setY(y);
            c.setScaleX(s);
            c.setScaleY(s);
            c.setAlpha(a);
        }
        updateDrawOrder();
        invalidate();
    }

    // ------------------------------------------------------------ 绘制

    /** 按「离中心从远到近」重排绘制顺序，居中那张最后画（压在最上面）。 */
    private void updateDrawOrder() {
        int n = getChildCount();
        if (n <= 1) {
            drawOrder = null;
            return;
        }
        if (drawOrder == null || drawOrder.length != n) {
            drawOrder = new int[n];
        }
        for (int i = 0; i < n; i++) {
            drawOrder[i] = i;
        }
        // 简单插入排序：卡片最多十来个，别为此引入 Comparator/装箱
        for (int i = 1; i < n; i++) {
            int v = drawOrder[i];
            float dv = Math.abs(distance(v));
            int j = i - 1;
            while (j >= 0 && Math.abs(distance(drawOrder[j])) < dv) {
                drawOrder[j + 1] = drawOrder[j];
                j--;
            }
            drawOrder[j + 1] = v;
        }
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (drawOrder == null || drawOrder.length != childCount) {
            return i;
        }
        return drawOrder[i];
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (count <= 0 || cardW == 0 || getChildCount() == 0) {
            return;
        }
        int idx = wrap(Math.round(offset));
        if (idx < 0 || idx >= getChildCount()) {
            return;
        }
        View c = getChildAt(idx);
        float s = c.getScaleX();
        float cx = c.getX() + cardW / 2f;
        float cy = c.getY() + cardH / 2f;
        float hw = cardW * s / 2f - dp(1);
        float hh = cardH * s / 2f - dp(1);
        float radius = dp(Theme.RADIUS_CARD) * s;
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh);

        // 焦点环跟着卡片一起淡出（上滑退场时不能留下一个孤零零的环）
        int cardAlpha = Math.round(c.getAlpha() * reflowAlpha * 255f);
        if (cardAlpha < 6) {
            return;
        }

        // 焦点环：紫→粉渐变，外面套三层递减透明度的描边当发光
        int a = theme == null ? 0xFF3DDC84 : theme.ringA;
        int b = theme == null ? 0xFF3DDC84 : theme.ringB;
        int glow = theme == null ? 0xFF3DDC84 : theme.ringGlow;
        ringPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                Theme.alphaOf(a, cardAlpha), Theme.alphaOf(b, cardAlpha), Shader.TileMode.CLAMP));
        glowPaint.setShader(null);
        for (int i = 0; i < GLOW_WIDTH.length; i++) {
            glowPaint.setStrokeWidth(dp(GLOW_WIDTH[i]) * s);
            glowPaint.setColor(Theme.alphaOf(glow, GLOW_ALPHA[i] * cardAlpha / 255));
            canvas.drawRoundRect(rect, radius, radius, glowPaint);
        }
        canvas.drawRoundRect(rect, radius, radius, ringPaint);
    }

    // ------------------------------------------------------------ 手势

    /**
     * 卡片不直接收手势：长按 / 点击 / 滑动都在这里统一判定。
     * 子 View 一旦拿到 ACTION_DOWN，父容器就收不到后续 MOVE，横滑会被吃掉。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return ev.getActionMasked() == MotionEvent.ACTION_DOWN;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (vt == null) {
            vt = VelocityTracker.obtain();
        }
        vt.addMovement(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 退场动画进行中：别让新的按下把卡片拽回来
                if (dismissAnimating) {
                    gesture = G_BLOCKED;
                    return true;
                }
                stopAnim();
                cancelSwipeAnim();
                cancelReflow();
                swipeIndex = -1;
                swipeDy = 0f;
                swipeAlpha = 1f;
                swipeScale = 1f;
                downX = ev.getX();
                downY = ev.getY();
                downOffset = offset;
                gesture = G_NONE;
                longPressed = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                removeCallbacks(longPressRunnable);
                if (count > 0) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (gesture == G_NONE) {
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        removeCallbacks(longPressRunnable);
                        if (Math.abs(dy) > Math.abs(dx)) {
                            gesture = G_V;
                            swipeIndex = count > 0 ? getSelected() : -1;
                            swipeDy = 0f;
                            swipeAlpha = 1f;
                            swipeScale = 1f;
                        } else {
                            gesture = G_H;
                        }
                    }
                }
                if (gesture == G_H) {
                    offset = boundOffset(downOffset - dx / step);
                    relayout();
                } else if (gesture == G_V && swipeIndex >= 0) {
                    dragSwipe(dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressRunnable);
                boolean cancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (gesture == G_H) {
                    float vx = 0f;
                    if (vt != null && !cancel) {
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                    }
                    snap(vx, cancel);
                } else if (gesture == G_V) {
                    float vy = 0f;
                    if (vt != null && !cancel) {
                        vt.computeCurrentVelocity(1000);
                        vy = vt.getYVelocity();
                    }
                    releaseSwipe(vy, cancel);
                } else if (!cancel && !longPressed && gesture == G_NONE) {
                    handleTap(ev.getX());
                }
                gesture = G_NONE;
                recycleVelocity();
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    /** 横滑松手：按拖动距离 + 甩动速度决定停在哪一张。 */
    private void snap(float vx, boolean cancel) {
        if (count <= 0) {
            return;
        }
        float fling = dp(640);
        float moved = offset - downOffset;
        int target = Math.round(offset);
        if (!cancel && vx < -fling) {
            target = (int) Math.ceil(offset - 0.0001f);
        } else if (!cancel && vx > fling) {
            target = (int) Math.floor(offset + 0.0001f);
        } else if (moved > 0.30f) {
            target = (int) Math.ceil(offset - 0.0001f);
        } else if (moved < -0.30f) {
            target = (int) Math.floor(offset + 0.0001f);
        }
        smoothTo(target);
    }

    /** 上滑松手：过半屏或快速上甩就飞出去，否则弹回原位。 */
    private void releaseSwipe(float vy, boolean cancel) {
        if (swipeIndex < 0) {
            return;
        }
        boolean dismiss = !cancel
                && (-swipeDy > cardH * 0.22f || vy < -dp(560));
        if (dismiss) {
            startSwipeOut();
        } else {
            springBack();
        }
    }

    /** 两端不加硬截断：拖出去一点点，松手会弹回来。 */
    private float boundOffset(float o) {
        if (loops()) {
            return o;
        }
        float max = count - 1;
        if (o < 0f) {
            return -Math.min(0.35f, -o * 0.4f);
        }
        if (o > max) {
            return max + Math.min(0.35f, (o - max) * 0.4f);
        }
        return o;
    }

    private void recycleVelocity() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    /**
     * 触点方向对应的那张卡片下标。
     */
    private int neighborIndex(int dir) {
        int sel = getSelected();
        int i = sel + dir;
        if (loops()) {
            return wrap(i);
        }
        return i >= 0 && i < count ? i : sel;
    }

    private void handleTap(float x) {
        if (count <= 0 || getWidth() == 0) {
            return;
        }
        float d = (x - getWidth() / 2f) / step;
        if (Math.abs(d) < 0.5f) {
            if (listener != null) {
                listener.onActivate(getSelected());
            }
        } else {
            smoothTo(neighborIndex(d > 0 ? 1 : -1));
        }
    }

    /** 触点落在哪张卡片上（长按进应用详情时用）。 */
    private int indexAt(float x) {
        if (count <= 0 || getWidth() == 0) {
            return -1;
        }
        float d = (x - getWidth() / 2f) / step;
        if (Math.abs(d) < 0.5f) {
            return getSelected();
        }
        return neighborIndex(d > 0 ? 1 : -1);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPressRunnable);
        recycleVelocity();
        cancelSwipeAnim();
        cancelReflow();
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
