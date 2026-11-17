package local.dsh.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 一组线性图标，直接画在 Canvas 上。
 *
 * 为什么不用图片：一是包体，二是这里是 20~24dp 的小图标，
 * 位图在 3 倍屏上会糊，矢量笔画一直是实的；三是换色只要改一个参数。
 * 全部按 24×24 网格设计，笔画 1.9、端点圆头，和系统图标一个口径。
 */
public class Glyph extends View {

    public static final int CHAT = 1;
    public static final int SMILE = 2;
    public static final int PLAY = 3;
    public static final int WALLET = 4;
    public static final int SUN = 5;
    public static final int MOON = 6;
    public static final int GLOBE = 7;
    public static final int GEAR = 8;
    public static final int HOME = 9;
    public static final int MINUS = 10;
    public static final int CHEVRON = 11;
    public static final int CHECK = 12;
    public static final int SHIELD = 13;
    public static final int POWER = 14;
    public static final int INFO = 15;
    public static final int DOTS = 16;
    public static final int GRID = 17;
    public static final int BACK = 18;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int kind;
    private int color;

    public Glyph(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        this.color = color;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.9f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    public void setGlyph(int kind) {
        this.kind = kind;
        invalidate();
    }

    public void setColor(int color) {
        this.color = color;
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) / 24f;
        c.save();
        c.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f);
        c.scale(s, s);
        // 笔画宽度按 24 网格的绝对值给，缩放之后就是「1.9/24 的图标边长」
        stroke.setStrokeWidth(1.9f);
        drawGlyph(c);
        c.restore();
    }

    private void drawGlyph(Canvas c) {
        switch (kind) {
            case CHAT:
                rect.set(3.2f, 4.4f, 20.8f, 15.6f);
                c.drawRoundRect(rect, 4.2f, 4.2f, stroke);
                path.reset();
                path.moveTo(7.6f, 15.4f);
                path.lineTo(7.6f, 20.4f);
                path.lineTo(12.4f, 15.4f);
                c.drawPath(path, stroke);
                c.drawLine(7.6f, 20.4f, 12.4f, 15.4f, stroke);
                c.drawLine(7.6f, 8.8f, 16.2f, 8.8f, stroke);
                c.drawLine(7.6f, 12.0f, 13.2f, 12.0f, stroke);
                break;

            case SMILE:
                c.drawCircle(12f, 12f, 8.4f, stroke);
                c.drawPoint(9.2f, 10.3f, dot());
                c.drawPoint(14.8f, 10.3f, dot());
                rect.set(7.9f, 8.9f, 16.1f, 16.3f);
                c.drawArc(rect, 22f, 136f, false, stroke);
                break;

            case PLAY:
                path.reset();
                path.moveTo(9.6f, 5.9f);
                path.lineTo(19.1f, 12f);
                path.lineTo(9.6f, 18.1f);
                path.close();
                Paint p = new Paint(fill);
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeWidth(1.6f);
                p.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(path, p);
                break;

            case WALLET:
                rect.set(3.1f, 6.3f, 20.9f, 18.4f);
                c.drawRoundRect(rect, 3.4f, 3.4f, stroke);
                c.drawLine(3.1f, 10.3f, 20.9f, 10.3f, stroke);
                c.drawCircle(17.1f, 14.4f, 1.35f, fill);
                break;

            case SUN:
                c.drawCircle(12f, 12f, 4.3f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float x1 = (float) (12 + Math.cos(a) * 6.6);
                    float y1 = (float) (12 + Math.sin(a) * 6.6);
                    float x2 = (float) (12 + Math.cos(a) * 8.7);
                    float y2 = (float) (12 + Math.sin(a) * 8.7);
                    c.drawLine(x1, y1, x2, y2, stroke);
                }
                break;

            case MOON: {
                Path a = new Path();
                a.addCircle(12f, 12f, 8.4f, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(15.4f, 9.2f, 7.6f, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                c.drawPath(a, fill);
                break;
            }

            case GLOBE:
                c.drawCircle(12f, 12f, 8.4f, stroke);
                rect.set(8.2f, 3.6f, 15.8f, 20.4f);
                c.drawOval(rect, stroke);
                c.drawLine(3.6f, 12f, 20.4f, 12f, stroke);
                break;

            case GEAR:
                c.drawCircle(12f, 12f, 3.1f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float x1 = (float) (12 + Math.cos(a) * 6.2);
                    float y1 = (float) (12 + Math.sin(a) * 6.2);
                    float x2 = (float) (12 + Math.cos(a) * 9.1);
                    float y2 = (float) (12 + Math.sin(a) * 9.1);
                    c.drawLine(x1, y1, x2, y2, stroke);
                }
                break;

            case HOME:
                path.reset();
                path.moveTo(3.9f, 11.2f);
                path.lineTo(12f, 4.1f);
                path.lineTo(20.1f, 11.2f);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(6.4f, 11.6f);
                path.lineTo(6.4f, 19.6f);
                path.lineTo(17.6f, 19.6f);
                path.lineTo(17.6f, 11.6f);
                c.drawPath(path, stroke);
                break;

            case MINUS:
                c.drawLine(7f, 12f, 17f, 12f, stroke);
                break;

            case CHEVRON:
                path.reset();
                path.moveTo(9.6f, 5.6f);
                path.lineTo(16f, 12f);
                path.lineTo(9.6f, 18.4f);
                c.drawPath(path, stroke);
                break;

            case BACK:
                path.reset();
                path.moveTo(14.4f, 5.6f);
                path.lineTo(8f, 12f);
                path.lineTo(14.4f, 18.4f);
                c.drawPath(path, stroke);
                break;

            case CHECK:
                path.reset();
                path.moveTo(5.2f, 12.6f);
                path.lineTo(10f, 17.4f);
                path.lineTo(18.8f, 6.6f);
                c.drawPath(path, stroke);
                break;

            case SHIELD:
                path.reset();
                path.moveTo(12f, 3.4f);
                path.lineTo(19.8f, 6.3f);
                path.lineTo(19.8f, 12.2f);
                path.cubicTo(19.8f, 16.6f, 16.4f, 19.6f, 12f, 20.8f);
                path.cubicTo(7.6f, 19.6f, 4.2f, 16.6f, 4.2f, 12.2f);
                path.lineTo(4.2f, 6.3f);
                path.close();
                c.drawPath(path, stroke);
                break;

            case POWER:
                rect.set(4.4f, 4.4f, 19.6f, 19.6f);
                c.drawArc(rect, -62f, 304f, false, stroke);
                c.drawLine(12f, 3.2f, 12f, 11.6f, stroke);
                break;

            case INFO:
                c.drawCircle(12f, 12f, 8.4f, stroke);
                c.drawPoint(12f, 8.2f, dot());
                c.drawLine(12f, 11.4f, 12f, 16.6f, stroke);
                break;

            case DOTS:
                c.drawPoint(6.4f, 12f, dot());
                c.drawPoint(12f, 12f, dot());
                c.drawPoint(17.6f, 12f, dot());
                break;

            case GRID:
                for (int i = 0; i < 4; i++) {
                    float x = (i % 2 == 0) ? 3.6f : 12.6f;
                    float y = (i < 2) ? 3.6f : 12.6f;
                    rect.set(x, y, x + 7.8f, y + 7.8f);
                    c.drawRoundRect(rect, 2.2f, 2.2f, stroke);
                }
                break;

            default:
                break;
        }
    }

    private Paint dot() {
        Paint p = new Paint(fill);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.6f);
        return p;
    }
}
