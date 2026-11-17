package local.dsh.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * 气泡：电脑上的鱼说了什么，这里逐字显示同一句，配图也是插件那张表情包。
 * 自己算高度，窗口按测量结果重设，所以长句子不会被切。
 */
public class PetBubble extends View {

    private static final int MAX_TEXT_LINES = 6;

    private final float density;
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path tail = new Path();

    private String text = "";
    private Bitmap image;
    private Layout layout;
    private int contentWidth;
    private int imageHeight;
    private boolean tailDown = true;
    private float tailAnchor = -1f;

    public PetBubble(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // 阴影要软件层

        boxPaint.setColor(0xF7FFFFFF);
        boxPaint.setShadowLayer(dp(6f), 0f, dp(2f), 0x33000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(0x1A000000);

        textPaint.setColor(0xFF1A1C20);
        textPaint.setTextSize(dp(13.5f));
    }

    private float dp(float v) {
        return v * density;
    }

    public void setContent(String text, Bitmap img, boolean tailDown) {
        this.text = text == null ? "" : text.trim();
        this.image = img;
        this.tailDown = tailDown;
        rebuild();
    }

    public String getText() {
        return text;
    }

    /** 小尾巴在气泡里的横向位置（相对气泡左边），用来对准宠物。 */
    public void setTailAnchor(float px) {
        if (Math.abs(px - tailAnchor) < 0.5f) return;
        tailAnchor = px;
        invalidate();
    }

    /** 气泡在宠物上方还是下方，决定小尾巴朝哪。 */
    public void setTailDown(boolean down) {
        if (this.tailDown == down) return;
        this.tailDown = down;
        invalidate();
    }

    private void rebuild() {
        contentWidth = Math.round(dp(210f));
        if (image != null) {
            float iw = Math.min(contentWidth, dp(132f));
            float ih = iw * image.getHeight() / (float) image.getWidth();
            float maxH = dp(118f);
            if (ih > maxH) {
                ih = maxH;
                iw = ih * image.getWidth() / (float) image.getHeight();
            }
            imageHeight = Math.round(ih);
        } else {
            imageHeight = 0;
        }

        if (text.isEmpty()) {
            layout = null;
        } else {
            layout = new StaticLayout(text, textPaint, contentWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.12f, dp(1.5f), false);
            if (layout.getLineCount() > MAX_TEXT_LINES) {
                String cut = text;
                while (cut.length() > 4) {
                    cut = cut.substring(0, cut.length() - 2);
                    StaticLayout tryLayout = new StaticLayout(cut + "…", textPaint, contentWidth,
                            Layout.Alignment.ALIGN_NORMAL, 1.12f, dp(1.5f), false);
                    if (tryLayout.getLineCount() <= MAX_TEXT_LINES) {
                        layout = tryLayout;
                        break;
                    }
                }
            }
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int pad = Math.round(dp(11f));
        int h = pad * 2;
        if (imageHeight > 0) h += imageHeight + Math.round(dp(7f));
        if (layout != null) h += layout.getHeight();
        h += Math.round(dp(5f)); // 尾巴
        int w = contentWidth + pad * 2;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int pad = Math.round(dp(11f));
        float w = getWidth();
        float bodyBottom = getHeight() - dp(5f);

        boxPaint.setShadowLayer(dp(7f), 0f, dp(2f), 0x38000000);
        RectF rect = new RectF(0f, 0f, w, bodyBottom);
        float radius = dp(13f);
        c.drawRoundRect(rect, radius, radius, boxPaint);
        c.drawRoundRect(rect, radius, radius, borderPaint);

        // 尾巴：指向宠物的那个小三角
        tail.reset();
        float anchor = tailAnchor >= 0f ? tailAnchor : w / 2f;
        float tx = Math.max(dp(18f), Math.min(w - dp(18f), anchor));
        if (tailDown) {
            tail.moveTo(tx - dp(6f), bodyBottom - dp(1f));
            tail.lineTo(tx, getHeight());
            tail.lineTo(tx + dp(6f), bodyBottom - dp(1f));
        } else {
            tail.moveTo(tx - dp(6f), dp(1f));
            tail.lineTo(tx, 0f);
            tail.lineTo(tx + dp(6f), dp(1f));
        }
        tail.close();
        c.drawPath(tail, boxPaint);

        float y = pad;
        if (image != null && imageHeight > 0) {
            float iw = Math.min(contentWidth, dp(132f));
            float ih = imageHeight;
            if (iw * image.getHeight() / (float) image.getWidth() > ih) {
                iw = ih * image.getWidth() / (float) image.getHeight();
            }
            float ix = (w - iw) / 2f;
            RectF dst = new RectF(ix, y, ix + iw, y + ih);
            c.drawBitmap(image, null, dst, imgPaint);
            y += ih + dp(7f);
        }

        if (layout != null) {
            c.save();
            c.translate(pad, y);
            layout.draw(c);
            c.restore();
        }
    }

    public static Bitmap roundCorners(Bitmap src, int radiusPx) {
        if (src == null) return null;
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Path p = new Path();
            p.addRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()),
                    radiusPx, radiusPx, Path.Direction.CW);
            c.clipPath(p);
            c.drawBitmap(src, 0, 0, null);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }
}
