package local.dsh.pet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 一套很小的设计系统。
 *
 * 工程里没有 AndroidX / Material，所以圆角、阴影、按压反馈、胶囊按钮全靠
 * GradientDrawable + RippleDrawable 手搓。好处是包体只有几百 KB，
 * 代价是所有尺寸必须自己定规矩——所以颜色、间距、圆角都收在这一个文件里，
 * 页面只描述结构，不写具体色值，改版时只动这里。
 *
 * 视觉口径：深海蓝（取自图标）＋ 大圆角卡片 ＋ 8pt 栅格 ＋ 一档阴影。
 */
public final class Ui {

    private Ui() {
    }

    /* ------------------------------ 颜色 ------------------------------ */

    public static final int BRAND = 0xFF0B62D8;
    public static final int BRAND_DEEP = 0xFF062E86;
    public static final int BRAND_MID = 0xFF0F6BE4;
    public static final int BRAND_LITE = 0xFF3FA4FF;
    public static final int CYAN = 0xFF57D3F0;

    public static final int INK = 0xFF0B1220;
    public static final int INK2 = 0xFF5B6679;
    public static final int INK3 = 0xFF98A2B3;
    public static final int INK4 = 0xFFC3CBD9;

    public static final int SURFACE = 0xFFFFFFFF;
    public static final int PAGE = 0xFFF2F7FE;
    public static final int FIELD = 0xFFF4F8FF;
    public static final int LINE = 0x14000000;
    public static final int LINE_STRONG = 0xFFE3ECF9;

    public static final int OK = 0xFF0FA968;
    public static final int BAD = 0xFFE5484D;
    public static final int WARN = 0xFFF2A20C;

    /* ------------------------------ 尺寸 ------------------------------ */

    public static final int R_CARD = 22;
    public static final int R_TILE = 16;
    public static final int R_FIELD = 14;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度；拿不到就用 24dp 兜底。 */
    public static int bar(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    /* ------------------------------ 形状 ------------------------------ */

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        return round(color, radiusDp, c, 0, 0);
    }

    public static GradientDrawable round(int color, float radiusDp, Context c,
                                         float strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return g;
    }

    public static GradientDrawable gradient(int[] colors, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable gradientV(int[] colors, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 只有按下才出现的浅色底：列表行、宫格块都用它，比整块高亮克制。 */
    public static Drawable pressable(Context c, int base, int pressed, float radiusDp) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusDp, c));
        d.addState(new int[]{}, base == Color.TRANSPARENT ? round(Color.TRANSPARENT, radiusDp, c)
                : round(base, radiusDp, c));
        return d;
    }

    /** 带水波纹的卡片/按钮底：API26 起原生支持，按下有一圈扩散。 */
    public static Drawable ripple(Context c, Drawable content, int rippleColor) {
        try {
            return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
        } catch (Throwable t) {
            return content;
        }
    }

    public static Drawable rippleRound(Context c, int fill, float radiusDp, int rippleColor) {
        return ripple(c, round(fill, radiusDp, c), rippleColor);
    }

    /* ------------------------------ 文字 ------------------------------ */

    public static TextView text(Context c, String s, float sp, int color) {
        return text(c, s, sp, color, Typeface.NORMAL);
    }

    public static TextView text(Context c, String s, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView bold(Context c, String s, float sp, int color) {
        return text(c, s, sp, color, Typeface.BOLD);
    }

    /** 小标题：全大写式的分组标签，压在卡片上方。 */
    public static TextView label(Context c, String s) {
        TextView t = text(c, s, 11.5f, INK3, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 6), 0, 0, dp(c, 8));
        return t;
    }

    /* ------------------------------ 容器 ------------------------------ */

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 白卡片：22dp 圆角 + 一档阴影，页面的基本单元。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(SURFACE, R_CARD, c));
        l.setElevation(dp(c, 2.5f));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpW(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpT(int w, int h, int topDp, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    /**
     * 「整行铺满」地塞进一个竖直容器。
     *
     * 手写布局最容易踩的坑：addView 不传 LayoutParams 时是 WRAP_CONTENT，
     * 里面的 weight=1 就全部失效，右侧控件会被挤成一团。
     * 所有整行元素都走这里，省得每处都写一遍。
     */
    public static void fill(LinearLayout parent, View child) {
        parent.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public static View spacer(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static View hairline(Context c, int leftDp) {
        View v = new View(c);
        v.setBackgroundColor(LINE_STRONG);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f)));
        p.leftMargin = dp(c, leftDp);
        v.setLayoutParams(p);
        return v;
    }

    /* ------------------------------ 组件 ------------------------------ */

    /** 主按钮：渐变胶囊，白字。 */
    public static TextView primary(Context c, String label, View.OnClickListener l) {
        TextView b = bold(c, label, 15f, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, gradient(new int[]{BRAND_MID, BRAND}, 999, c), 0x33FFFFFF));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 50));
        b.setElevation(dp(c, 1.5f));
        b.setOnClickListener(l);
        return b;
    }

    /** 次按钮：白底细描边胶囊。 */
    public static TextView secondary(Context c, String label, View.OnClickListener l) {
        TextView b = bold(c, label, 14f, BRAND);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, round(0xFFFFFFFF, 999, c, 1, LINE_STRONG), 0x1A0B62D8));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 46));
        b.setOnClickListener(l);
        return b;
    }

    /** 深色底上的半透明按钮（压在渐变色 Hero 上）。 */
    public static TextView ghostOnDark(Context c, String label, View.OnClickListener l) {
        TextView b = bold(c, label, 14f, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, round(0x33FFFFFF, 999, c, 1, 0x40FFFFFF), 0x33FFFFFF));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 46));
        b.setOnClickListener(l);
        return b;
    }

    /** 状态胶囊：一个小圆点 + 文案。 */
    public static LinearLayout chip(Context c, String text, int dotColor, int textColor, int bg) {
        LinearLayout l = row(c);
        l.setBackground(round(bg, 999, c));
        l.setPadding(dp(c, 9), dp(c, 5), dp(c, 11), dp(c, 5));
        View d = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(dotColor);
        d.setBackground(g);
        l.addView(d, new LinearLayout.LayoutParams(dp(c, 6), dp(c, 6)));
        TextView t = text(c, text, 11.5f, textColor, Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(c, 6);
        l.addView(t, tp);
        return l;
    }

    /** 输入框：无下划线、浅蓝底、14dp 圆角——默认 EditText 那根线太旧了。 */
    public static android.widget.EditText field(Context c, String value, String hint) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(13.5f);
        e.setTextColor(INK);
        e.setHintTextColor(INK4);
        e.setSingleLine(false);
        e.setMaxLines(2);
        e.setBackground(round(FIELD, R_FIELD, c));
        int p = dp(c, 13);
        e.setPadding(p, p, p, p);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    /** 带标签的输入块。 */
    public static android.widget.EditText labeledField(Context c, LinearLayout parent,
                                                      String label, String value, String hint) {
        TextView l = text(c, label, 11.5f, INK3, Typeface.BOLD);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp(c, 12);
        parent.addView(l, lp1);
        android.widget.EditText e = field(c, value, hint);
        parent.addView(e, lpT(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, c));
        return e;
    }

    /** 一行设置项：图标 + 标题/说明 + 右侧控件。 */
    public static LinearLayout settingRow(Context c, int glyph, String title, String sub,
                                          View trailing) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 4), dp(c, 11), dp(c, 4), dp(c, 11));

        if (glyph != 0) {
            Glyph g = new Glyph(c, glyph, BRAND);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22));
            gp.rightMargin = dp(c, 12);
            r.addView(g, gp);
        }

        LinearLayout col = column(c);
        col.addView(text(c, title, 14f, INK));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11.5f, INK3);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(c, 3);
            col.addView(s, sp);
        }
        r.addView(col, lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) r.addView(trailing);
        return r;
    }

    /** 开关：拇指白、轨道蓝，跟整体一致。 */
    public static android.widget.Switch toggle(Context c, boolean checked) {
        android.widget.Switch s = new android.widget.Switch(c);
        s.setChecked(checked);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        s.setThumbTintList(new ColorStateList(states,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states,
                new int[]{BRAND, 0xFFD7E3F4}));
        s.setPadding(0, 0, 0, 0);
        return s;
    }

    /** 左图右文的可点行：用于「更多」列表。 */
    public static LinearLayout listRow(Context c, int glyph, String title, String sub,
                                       Runnable click) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 10), dp(c, 12), dp(c, 10), dp(c, 12));
        r.setBackground(pressable(c, Color.TRANSPARENT, 0x0F0B62D8, 12));

        if (glyph != 0) {
            Glyph g = new Glyph(c, glyph, INK2);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
            gp.rightMargin = dp(c, 12);
            r.addView(g, gp);
        }
        LinearLayout col = column(c);
        col.addView(text(c, title, 14f, INK));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11f, INK3);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(c, 2);
            col.addView(s, sp);
        }
        r.addView(col, lpW(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Glyph chev = new Glyph(c, Glyph.CHEVRON, INK4);
        r.addView(chev, new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)));
        r.setClickable(true);
        r.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            click.run();
        });
        return r;
    }

    /** 宫格里的一格：圆形淡底 + 线性图标 + 文案。 */
    public static LinearLayout tile(Context c, int glyph, String label, Runnable click) {
        LinearLayout l = column(c);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(0, dp(c, 8), 0, dp(c, 8));
        l.setBackground(pressable(c, Color.TRANSPARENT, 0x0F0B62D8, R_TILE));

        LinearLayout bubble = new LinearLayout(c);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(round(0xFFEAF3FF, 16, c));
        bubble.addView(new Glyph(c, glyph, BRAND), new LinearLayout.LayoutParams(
                dp(c, 22), dp(c, 22)));
        l.addView(bubble, new LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)));

        TextView t = text(c, label, 11.5f, INK2, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(c, 7);
        l.addView(t, tp);

        l.setClickable(true);
        l.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            click.run();
        });
        return l;
    }

    /** 数字/字母序号圆点，用于「三步走」引导。 */
    public static TextView stepBadge(Context c, String s, boolean done) {
        TextView t = bold(c, s, 12.5f, done ? 0xFFFFFFFF : BRAND);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(done ? OK : 0xFFE7F0FF, 999, c));
        return t;
    }

    public static void setMargins(View v, Context c, int l, int t, int r, int b) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) raw;
            p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
            v.setLayoutParams(p);
        } else if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) raw;
            p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
            v.setLayoutParams(p);
        }
    }

    /** sp → px，画布上写字时用。 */
    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }
}
