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
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.util.List;

/**
 * 横向卡片轮播：中间一张永远居中、全尺寸、高亮；左右各露出一半。
 *
 * step = 宽度的一半 => 相邻卡片露出的部分正好是卡片宽度的一半。
 * 卡片数量 >= 3 时循环：第一张的左边永远是最后一张，不会出现空位。
 * 滚动用 ValueAnimator + PathInterpolator（ease-out）驱动，拖拽后自动吸附。
 */
public class CarouselView extends ViewGroup {

    public interface Listener {
        void onSelected(int index);
        void onActivate(int index);
    }

    private static final float CARD_RATIO = 0.42f;   // 卡片宽 / 屏宽
    private static final float SCALE_MIN = 0.86f;   // 侧边卡片的缩放
    private static final float ALPHA_MIN = 0.42f;   // 侧边卡片的透明度

    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint();
    private final RectF rect = new RectF();
    private LinearGradient fadeLeft;
    private LinearGradient fadeRight;
    private int fadeW;

    private int cardW;
    private int cardH;
    private int step;
    private float offset;
    private int count;
    private ValueAnimator anim;
    private Listener listener;
    private int lastSelected = -1;

    private float downX;
    private float downOffset;
    private boolean dragging;

    public CarouselView(Context context) {
        this(context, null);
    }

    public CarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2.5f));
        ringPaint.setColor(0xFF3DDC84);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(11));
        glowPaint.setColor(0x2E3DDC84);
        setClipChildren(true);
        setClickable(true);
        // 别让系统画默认的灰白焦点蒙版：按键都在 Activity 层处理，不需要 View 焦点
        setFocusable(false);
        setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
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
        anim.setDuration(340);
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
        fadeW = Math.round(w * 0.09f);
        fadeLeft = new LinearGradient(0, 0, fadeW, 0,
                0xFF0B0E13, 0x000B0E13, Shader.TileMode.CLAMP);
        fadeRight = new LinearGradient(w, 0, w - fadeW, 0,
                0xFF0B0E13, 0x000B0E13, Shader.TileMode.CLAMP);
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
        relayout();
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
            c.setX(cx + d * step);
            c.setY(cy);
            c.setScaleX(s);
            c.setScaleY(s);
            c.setAlpha(1f - (1f - ALPHA_MIN) * f);
        }
        invalidate();
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (fadeLeft != null) {
            fadePaint.setShader(fadeLeft);
            canvas.drawRect(0, 0, fadeW, getHeight(), fadePaint);
            fadePaint.setShader(fadeRight);
            canvas.drawRect(getWidth() - fadeW, 0, getWidth(), getHeight(), fadePaint);
        }
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
        float radius = dp(20) * s;
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        canvas.drawRoundRect(rect, radius, radius, glowPaint);
        canvas.drawRoundRect(rect, radius, radius, ringPaint);
    }

    // ------------------------------------------------------------ 手势

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopAnim();
                downX = ev.getX();
                downOffset = offset;
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                if (!dragging && Math.abs(dx) > dp(8)) {
                    dragging = true;
                }
                if (dragging) {
                    float o = downOffset - dx / step;
                    if (!loops()) {
                        o = Math.max(-0.12f, Math.min(count - 1 + 0.12f, o));
                    }
                    offset = o;
                    relayout();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    handleTap(ev.getX());
                } else {
                    float moved = offset - downOffset;
                    int target = Math.round(offset);
                    if (Math.abs(moved) > 0.22f) {
                        target = moved > 0 ? (int) Math.ceil(offset) : (int) Math.floor(offset);
                    }
                    smoothTo(target);
                }
                dragging = false;
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
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
            smoothTo(Math.round(offset) + (d > 0 ? 1 : -1));
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
