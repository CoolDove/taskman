package app.dsh.running;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 背景层：竖向渐变打底，再叠两团柔光（左上紫、右下蓝）。
 *
 * 没有 RenderEffect / 模糊，用「径向渐变淡出到全透明」近似设计稿里的环境光，
 * 在 1080p 掌机上只看大面积色晕，够用且不花性能。
 */
final class Backdrop extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private int top, bottom, glowA, glowB;
    private Shader bgShader, glowShaderA, glowShaderB;

    Backdrop(Context context) {
        super(context);
    }

    void setPalette(int top, int bottom, int glowA, int glowB) {
        this.top = top;
        this.bottom = bottom;
        this.glowA = glowA;
        this.glowB = glowB;
        bgShader = null;
        glowShaderA = null;
        glowShaderB = null;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bgShader = null;
        rebuild();
    }

    private void rebuild() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (bgShader == null) {
            // 稍微斜一点，避免大面积纯竖条
            bgShader = new LinearGradient(0, 0, w * 0.45f, h,
                    top, bottom, Shader.TileMode.CLAMP);
        }
        if (glowShaderA == null) {
            float r = Math.max(w, h) * 0.6f;
            glowShaderA = new RadialGradient(w * 0.14f, h * -0.10f, r,
                    new int[]{glowA, transparent(glowA)}, new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            glowShaderB = new RadialGradient(w * 0.88f, h * 1.12f, r,
                    new int[]{glowB, transparent(glowB)}, new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
        }
    }

    private static int transparent(int color) {
        return color & 0x00FFFFFF;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rebuild();
        int w = getWidth();
        int h = getHeight();
        if (bgShader == null) {
            return;
        }
        paint.setShader(bgShader);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(glowShaderA);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(glowShaderB);
        canvas.drawRect(0, 0, w, h, paint);
    }
}
