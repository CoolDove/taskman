package app.dsh.running;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 亮/暗两套配色，跟随系统主题（uiMode）。
 *
 * 深色是「暗紫蓝渐变 + 白色胶囊」，亮色是「淡紫蓝渐变 + 白卡片」，
 * 两者共用同一套紫→粉的焦点环。卡片直接浮在渐变背景上，靠阴影分层。
 * 所有 UI 颜色都从这里取，别在别的类里写死色值。
 */
final class Theme {

    /** 卡片圆角；CarouselView 画渐变描边时要跟着用。 */
    static final int RADIUS_CARD = 24;
    static final int RADIUS_ART = 18;

    boolean dark;

    // 背景：竖向渐变 + 左上紫、右下蓝两团环境光
    int bgTop, bgBottom, glowA, glowB;
    // 卡片
    int card, cardStroke, tileTop, tileBottom, shadow;
    // 文字
    int text, sub, pkg;
    // 焦点环（紫→粉）
    int ringA, ringB, ringGlow;
    // 底部按键胶囊
    int pillBg, pillStroke, pillText, pillKey;
    // 状态点
    int green, dotOff;
    // 应用内提示条
    int bannerBg, bannerText, bannerStroke;
    // Shizuku 未运行提示
    int warnBg, warnStroke, warnText, warnDot;

    private Theme() {
    }

    static Theme of(Context c) {
        boolean dark;
        try {
            dark = (c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            dark = true;
        }
        Theme t = new Theme();
        t.dark = dark;
        if (dark) {
            t.bgTop = 0xFF111119;
            t.bgBottom = 0xFF1D1E2C;
            t.glowA = 0x5C7C4DFF;
            t.glowB = 0x4A2F6BFF;

            t.card = 0xFF2A2C3D;
            t.cardStroke = 0x24FFFFFF;
            t.tileTop = 0xFF35384F;
            t.tileBottom = 0xFF242636;
            t.shadow = 0x99000000;

            t.text = 0xFFF4F5FA;
            t.sub = 0xFFA6ABC2;
            t.pkg = 0xFF7C8199;

            t.ringA = 0xFFB06BF7;
            t.ringB = 0xFFF461C8;
            t.ringGlow = 0xFFA96BF7;

            t.pillBg = 0xFF232636;
            t.pillStroke = 0x2EFFFFFF;
            t.pillText = 0xFFD3D7E6;
            t.pillKey = 0xFFCB9BFF;

            t.green = 0xFF3DDC84;
            t.dotOff = 0xFF586079;

            t.bannerBg = 0xF235384F;
            t.bannerText = 0xFFF2F3FA;
            t.bannerStroke = 0x33FFFFFF;

            t.warnBg = 0xFF2A2118;
            t.warnStroke = 0x66FFB86B;
            t.warnText = 0xFFFFC98C;
            t.warnDot = 0xFFFFB86B;
        } else {
            t.bgTop = 0xFFF5F7FE;
            t.bgBottom = 0xFFDBE3F8;
            t.glowA = 0x55A98BFF;
            t.glowB = 0x552E7BFF;

            t.card = 0xFFFFFFFF;
            t.cardStroke = 0xFFDEE3F3;
            t.tileTop = 0xFFF4F6FE;
            t.tileBottom = 0xFFE6EAF8;
            t.shadow = 0x4A2B3060;

            t.text = 0xFF171927;
            t.sub = 0xFF5C6379;
            t.pkg = 0xFF8A90A6;

            t.ringA = 0xFF7B4DE8;
            t.ringB = 0xFFE055C8;
            t.ringGlow = 0xFF8E5CF0;

            t.pillBg = 0xFFFFFFFF;
            t.pillStroke = 0xFFE4E8F6;
            t.pillText = 0xFF171927;
            t.pillKey = 0xFF6A3BE0;

            t.green = 0xFF10A45E;
            t.dotOff = 0xFFB9BFD2;

            t.bannerBg = 0xFF1B1D29;
            t.bannerText = 0xFFF6F7FB;
            t.bannerStroke = 0x00000000;

            t.warnBg = 0xFFFFF4E5;
            t.warnStroke = 0xFFF0D5B0;
            t.warnText = 0xFF8A5A17;
            t.warnDot = 0xFFE8912B;
        }
        return t;
    }

    /** 取同一个 RGB、换成指定不透明度（0..255）。 */
    static int alphaOf(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
