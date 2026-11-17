package local.dsh.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * 悬浮球本体：画的就是插件里那张「蓝毛小女仆」立绘，App 名叫鱼球。
 *
 * 立绘只有一张静态图，所以「动作」全靠程序化变形做：呼吸、上下浮、点击弹跳、
 * 拖拽时按速度倾斜、睡觉时冒 Zzz、电脑上的鱼在忙时脑袋后面有一圈呼吸光。
 *
 * 关键点：窗口比立绘大一圈（padX / padY），所有变形都在这一圈留白里做，
 * 再按包围盒做一次「容得下才缩放」的兜底，所以呼吸、弹跳、倾斜都不会被窗口切掉一角。
 * 贴边停靠时窗口被裁窄，用 clipShift 把立绘往外推，裁掉的永远是透明留白。
 */
public class PetView extends View {

    public interface Listener {
        int SWIPE_LEFT = -1;
        int SWIPE_RIGHT = 1;
        int SWIPE_UP = 2;
        int SWIPE_DOWN = 3;

        /** 拖动增量，单位 px。 */
        void onDrag(float dx, float dy, float vx);

        /** 手指抬起，该吸附边缘了。 */
        void onRelease();

        /** 短按（等了 280ms 确认不是双击）。 */
        void onTap();

        /** 双击：直接打开 dsh。 */
        void onDoubleTap();

        /** 快速滑动，dir 是 SWIPE_* 之一。 */
        void onSwipe(int dir);

        /** 从贴边状态被摸了一下（这时先滑出来，不打开菜单）。 */
        void onWakeFromEdge();

        /** 长按：摸摸它，不是关闭。 */
        void onLongPressPet();
    }

    private static final long LONG_PRESS_MS = 560;
    private static final long DOUBLE_MS = 300;
    private static final float TILT_MAX = 4f;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix artMatrix = new Matrix();
    private final RectF artSrc = new RectF();

    private final float density;
    private final Bitmap art;

    private Listener listener;

    private boolean pressed;
    private boolean dragging;
    private boolean longPressFired;
    private boolean clickFired;
    private boolean tapPending;
    private boolean doubleCandidate;
    private float downX, downY, lastX, lastY;
    private float totalDx, totalDy;
    private long downAt;
    private float velocity;

    private float clipShift;
    private int artWidth;
    private int padX, padY;
    private float tilt;
    private float alpha = 1f;
    private float pressScale = 1f;
    private boolean sleeping;
    private boolean working;

    /* 本机演出：电脑上的动作名翻成 16 种式子，这里只负责按时间采样 */
    private int motionKind = PetAction.NONE;
    private long motionAt;
    private int motionDur = 720;
    private float mSx = 1f, mSy = 1f, mDx, mDy, mRot;

    /* 真实动画：和电脑上同一段素材转出来的逐帧 WebP，播一遍就回静态立绘 */
    private android.graphics.drawable.Drawable clip;
    private String clipName = "";
    private long clipAt;

    private RadialGradient shadowGradient;
    private int shadowW, shadowH;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postOnAnimationDelayed(this, 33);
        }
    };

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!pressed || dragging) return;
            longPressFired = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onLongPressPet();
        }
    };

    /** 单击要等一个双击窗口，否则每次点都会先闪一下菜单再跳走。 */
    private final Runnable singleTap = new Runnable() {
        @Override
        public void run() {
            tapPending = false;
            if (listener != null) listener.onTap();
        }
    };

    public PetView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        art = BitmapFactory.decodeResource(getResources(), R.drawable.pet_main);

        zPaint.setColor(0xFF2B3A67);
        zPaint.setTypeface(Typeface.DEFAULT_BOLD);
        zPaint.setTextSize(dp(11f));
        zPaint.setShadowLayer(dp(2f), 0, dp(1f), 0x33FFFFFF);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 立绘基准宽度（不含留白），窗口被裁窄时仍按这个宽度画，裁掉的是留白。 */
    public void setBaseWidth(int px) {
        artWidth = px;
        invalidate();
    }

    /** 四周留白：呼吸 / 弹跳 / 倾斜都在这块空间里发生，保证立绘完整。 */
    public void setArtPadding(int x, int y) {
        padX = x;
        padY = y;
        invalidate();
    }

    /** 贴边时把立绘往外推，视图本身宽度就是露出来的那一条。 */
    public void setClipShift(float px) {
        clipShift = px;
        invalidate();
    }

    public void setPetState(boolean sleeping, boolean working) {
        this.sleeping = sleeping;
        this.working = working;
        invalidate();
    }

    public void hop() {
        play(PetAction.HOP);
    }

    /**
     * 播一段本机演出。正在演的时候再来一个就打断——
     * 连点两下菜单它跟着抖两下，比排队播完更贴着人的预期。
     */
    public void play(int preset) {
        if (preset == PetAction.NONE) return;
        motionKind = preset;
        motionAt = System.currentTimeMillis();
        motionDur = PetAction.durationFor(preset);
        invalidate();
    }

    public boolean isPlaying() {
        return motionKind != PetAction.NONE || clip != null;
    }

    /** 正在播的真实动画名，没在播就是空串。 */
    public String playingClip() {
        return clipName;
    }

    /**
     * 播一段真实动画（电脑上同一段素材转出来的 WebP）。
     *
     * 返回 false 表示这次没接住——比如系统低于 8.0、解码失败。
     * 调用方这时退回程序化演出，用户看到的仍然是「点下去有反应」。
     */
    public boolean playClip(String name, android.graphics.drawable.Drawable d) {
        if (d == null) return false;
        stopClip();
        clip = d;
        clipName = name == null ? "" : name;
        clipAt = System.currentTimeMillis();
        d.setCallback(this);
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            stopClip();
            return false;
        }
        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
            android.graphics.drawable.AnimatedImageDrawable a =
                    (android.graphics.drawable.AnimatedImageDrawable) d;
            a.setRepeatCount(0);
            a.start();
        }
        invalidate();
        return true;
    }

    private void stopClip() {
        if (clip != null) {
            if (clip instanceof android.graphics.drawable.AnimatedImageDrawable) {
                ((android.graphics.drawable.AnimatedImageDrawable) clip).stop();
            }
            clip.setCallback(null);
        }
        clip = null;
        clipName = "";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postOnAnimation(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        removeCallbacks(longPress);
        removeCallbacks(singleTap);
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (art == null) return;

        float w = getWidth(), h = getHeight();
        // 以「完整窗口宽度」为基准排版：贴边裁窄时视图会变窄，但立绘不能跟着缩。
        float fullW = artWidth > 0 ? artWidth + padX * 2 : w;
        float baseW = artWidth > 0 ? artWidth : Math.max(dp(8f), fullW - padX * 2);
        float scale0 = baseW / art.getWidth();
        float bw = art.getWidth() * scale0;
        float bh = art.getHeight() * scale0;
        float left = padX + clipShift;
        float bottom = h - padY;
        float top = bottom - bh;

        long now = System.currentTimeMillis();
        float ph = (now % (sleeping ? 7200L : 4600L)) / (float) (sleeping ? 7200L : 4600L);
        float breathe = 1f + (sleeping ? 0.010f : 0.022f) * (float) Math.sin(ph * Math.PI * 2);
        float bob = dp(sleeping ? 0.8f : 1.8f) * (float) Math.sin(ph * Math.PI * 2 + 0.5f);

        float hopScale = 1f;
        if (motionKind != PetAction.NONE) {
            float t = (now - motionAt) / (float) motionDur;
            if (t >= 1f) {
                motionKind = PetAction.NONE;
                mSx = mSy = 1f;
                mDx = mDy = mRot = 0f;
            } else {
                sample(motionKind, t);
                hopScale = 1f;
            }
        }

        // 工作状态的光晕
        if (working && !sleeping) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now % 1600L / 1600f * Math.PI * 2);
            float cx = left + bw / 2f;
            float cy = top + bh * 0.45f;
            float r = bw * (0.58f + 0.05f * pulse);
            haloPaint.setShader(new RadialGradient(cx, cy, Math.max(r, dp(1f)),
                    new int[]{0x552F6BFF, 0x002F6BFF}, null, Shader.TileMode.CLAMP));
            haloPaint.setAlpha((int) (110 + 90 * pulse));
            c.drawCircle(cx, cy, r, haloPaint);
            haloPaint.setShader(null);
        }

        // 脚下阴影
        ensureShadowGradient(bw, bh);
        float sy = top + bh - dp(1f);
        RectF sr = new RectF(left + bw * 0.16f, sy - dp(5f),
                left + bw * 0.84f, sy + dp(4f));
        shadowPaint.setShader(shadowGradient);
        c.save();
        c.translate(sr.centerX(), sr.centerY());
        c.scale(1f, 0.34f);
        c.drawCircle(0f, 0f, sr.width() / 2f, shadowPaint);
        c.restore();
        shadowPaint.setShader(null);

        // 真实动画在播：按「和静态立绘同高、底边中点对齐」贴上去。
        // 素材已经是裁到角色包围盒的，所以两者换进换出不会跳位置。
        if (clip != null) {
            int cw = clip.getIntrinsicWidth(), chh = clip.getIntrinsicHeight();
            boolean ended = clip instanceof android.graphics.drawable.AnimatedImageDrawable
                    && !((android.graphics.drawable.AnimatedImageDrawable) clip).isRunning()
                    && now - clipAt > 240;
            if (cw <= 0 || chh <= 0 || ended || now - clipAt > 12000) {
                stopClip();
                invalidate();
            } else {
                float cs = bh / chh;
                float cwid = cw * cs;
                c.save();
                c.translate(left + bw / 2f - cwid / 2f, top + bob + bh - chh * cs);
                c.scale(cs, cs);
                clip.setBounds(0, 0, cw, chh);
                clip.draw(c);
                c.restore();
                return;
            }
        }

        // 立绘：以底边中点为锚点做呼吸 + 倾斜 + 本机演出
        float anchorX = left + bw / 2f;
        float anchorY = top + bob + bh;
        float scaleAll = breathe * pressScale * hopScale;
        float sclX = scaleAll * mSx;
        float sclY = scaleAll * mSy;
        int rot = Math.round(tilt + mRot);

        // 倾斜 / 旋转 / 缩放后的包围盒先算一遍：极端组合（演出 + 拖拽倾斜同时发生）时
        // 只缩一点点，保证四个角都留在窗口里，绝不会被裁掉一截。
        float rad = (float) Math.toRadians(rot);
        float cos = Math.abs((float) Math.cos(rad));
        float sin = Math.abs((float) Math.sin(rad));
        float sc = Math.max(Math.abs(sclX), Math.abs(sclY));
        float bboxW = bw * sc * cos + bh * sc * sin;
        float bboxH = bh * sc * cos + bw * sc * sin;
        float availW = Math.max(dp(8f), fullW - 2f);
        float availH = Math.max(dp(8f), h - 2f);
        float fit = Math.min(availW / Math.max(bboxW, 1f),
                availH / Math.max(bboxH, 1f));
        if (fit < 1f) {
            sclX *= fit;
            sclY *= fit;
        }

        artMatrix.reset();
        // 先各自轴向缩放（挤 / 弹 / 翻转都靠它），再把「缩放后的底边中点」对回原位，
        // 最后绕这个点旋转——所以压扁时是往下坐，不是整只往右下缩。
        artMatrix.setScale(scale0 * sclX, scale0 * sclY);
        artMatrix.postTranslate(anchorX - bw * sclX / 2f, anchorY - bh * sclY);
        if (mDx != 0f || mDy != 0f) artMatrix.postTranslate(mDx, mDy);
        if (rot != 0) artMatrix.postRotate(rot, anchorX, anchorY);
        artSrc.set(0f, 0f, art.getWidth(), art.getHeight());
        mapRect(artMatrix, artSrc);

        // 旋转会把包围盒整体挪一点，挪回来，别让任何一边贴出窗口。
        // 贴边收进一半时窗口本来就比立绘窄，这时是要裁的，不能再往回挪。
        boolean cropped = w < fullW - 1f;
        float dx = 0f, dy = 0f;
        if (!cropped) {
            if (mapped.left < 0f) dx = -mapped.left;
            else if (mapped.right > w) dx = w - mapped.right;
        }
        if (mapped.top < 0f) dy = -mapped.top;
        else if (mapped.bottom > h) dy = h - mapped.bottom;
        if (dx != 0f || dy != 0f) artMatrix.postTranslate(dx, dy);

        bitmapPaint.setAlpha((int) (255 * alpha));
        c.drawBitmap(art, artMatrix, bitmapPaint);

        // 睡觉时冒 Zzz
        if (sleeping) {
            float t = (now % 2600L) / 2600f;
            for (int i = 0; i < 3; i++) {
                float p = (t + i * 0.33f) % 1f;
                float zx = left + bw * (0.74f + 0.10f * p);
                float zy = top + bh * (0.30f - 0.20f * p);
                zPaint.setTextSize(dp(8f + 5f * p));
                zPaint.setAlpha((int) (200 * (1f - p)));
                c.drawText("z", zx, zy, zPaint);
            }
            zPaint.setAlpha(255);
        }
    }

    private final RectF mapped = new RectF();

    /**
     * 按进度 t（0→1）算这一帧的形变。
     *
     * 全部写进字段而不是构造对象：这是每帧都跑的函数，一秒三十次，
     * 一分钱的对象都不该在这里产生。
     * 幅度都控制在「一眼能看出来但不至于变形」的范围内——这是只宠物，不是加载动画。
     */
    private void sample(int kind, float t) {
        mSx = 1f;
        mSy = 1f;
        mDx = 0f;
        mDy = 0f;
        mRot = 0f;
        // 退场包络：所有式子都乘上它，结尾自然回到原样，不会硬切
        float ease = (float) Math.sin(Math.PI * Math.min(1f, Math.max(0f, t)));
        float tau = (float) (Math.PI * 2);

        // 幅度总体按「一眼能看出来」调：之前只有 5%、7dp，在 78dp 宽的立绘上
        // 肉眼几乎看不出形变，用户反馈就是「点了没动作」。
        switch (kind) {
            case PetAction.HOP:
                mDy = -dp(18f) * ease;
                mSx = 1f - 0.10f * ease;
                mSy = 1f + 0.16f * ease;
                break;

            case PetAction.SQUASH:
                mSy = 1f - 0.28f * ease;
                mSx = 1f + 0.22f * ease;
                break;

            case PetAction.STRETCH:
                mSy = 1f + 0.26f * ease;
                mSx = 1f - 0.15f * ease;
                mDy = -dp(6f) * ease;
                break;

            case PetAction.SPIN: {
                // 绕竖轴转，而不是在屏幕平面里转。
                // 平面旋转会让包围盒瞬间变大（45° 时最宽），窗口没有那么多空白，
                // 兜底缩放会把鱼压小一截——看起来像在抽。竖轴转只改 scaleX，永远不会溢出。
                float c = (float) Math.cos(t * tau * 2f);
                mSx = Math.abs(c) < 0.06f ? (c < 0 ? -0.06f : 0.06f) : c;
                mDy = -dp(7f) * ease;
                break;
            }

            case PetAction.SWAY:
                mRot = 14f * (float) Math.sin(t * tau * 2.5f) * (1f - 0.30f * t);
                mDx = dp(4f) * (float) Math.sin(t * tau * 2.5f + 0.6f);
                break;

            case PetAction.SHAKE:
                mDx = dp(7f) * (float) Math.sin(t * tau * 7f) * (1f - t);
                mRot = 5f * (float) Math.sin(t * tau * 7f) * (1f - t);
                break;

            case PetAction.NOD:
                mDy = dp(5f) * (float) Math.sin(t * tau * 3f);
                mSy = 1f - 0.05f * (float) Math.abs(Math.sin(t * tau * 3f));
                break;

            case PetAction.BOW:
                mRot = 24f * ease;
                mDy = dp(6f) * ease;
                break;

            case PetAction.RUN:
                mDx = dp(14f) * (float) Math.sin(t * tau * 2f);
                mRot = 9f * (float) Math.sin(t * tau * 2f);
                mDy = -dp(8f) * (float) Math.abs(Math.sin(t * tau * 2f));
                break;

            case PetAction.CHEW:
                mSy = 1f - 0.12f * (float) Math.abs(Math.sin(t * tau * 4f)) * ease;
                mSx = 1f + 0.08f * (float) Math.abs(Math.sin(t * tau * 4f)) * ease;
                mRot = 3f * (float) Math.sin(t * tau * 4f) * ease;
                break;

            case PetAction.DOZE:
                mDy = dp(12f) * t;
                mRot = -10f * t;
                mSy = 1f - 0.06f * t;
                mSx = 1f + 0.05f * t;
                break;

            case PetAction.GROW:
                mSx = 1f + 0.30f * ease;
                mSy = 1f + 0.30f * ease;
                break;

            case PetAction.WOBBLE:
                mRot = 16f * (float) Math.sin(t * tau * 3f) * (1f - t) * (1f - t);
                break;

            case PetAction.FLIP: {
                float c = (float) Math.cos(t * tau);
                mSx = (Math.abs(c) < 0.08f ? (c < 0 ? -0.08f : 0.08f) : c);
                mRot = 8f * (float) Math.sin(t * tau);
                mDy = -dp(5f) * ease;
                break;
            }

            case PetAction.DIZZY: {
                float c = (float) Math.cos(t * tau * 3f);
                mSx = Math.abs(c) < 0.1f ? (c < 0 ? -0.1f : 0.1f) : c;
                mRot = 12f * (float) Math.sin(t * tau * 4f);
                mDx = dp(6f) * (float) Math.sin(t * tau * 2f);
                break;
            }

            case PetAction.POP:
            default:
                mSx = 1f + 0.16f * ease;
                mSy = 1f + 0.16f * ease;
                mDy = -dp(6f) * ease;
                break;
        }
    }

    /** 把矩阵变换后的包围盒写进 mapped（复用对象，每帧不产生垃圾）。 */
    private void mapRect(Matrix m, RectF src) {
        mapped.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        float[] pts = new float[]{
                src.left, src.top,
                src.right, src.top,
                src.right, src.bottom,
                src.left, src.bottom
        };
        m.mapPoints(pts);
        for (int i = 0; i < pts.length; i += 2) {
            if (pts[i] < mapped.left) mapped.left = pts[i];
            if (pts[i] > mapped.right) mapped.right = pts[i];
            if (pts[i + 1] < mapped.top) mapped.top = pts[i + 1];
            if (pts[i + 1] > mapped.bottom) mapped.bottom = pts[i + 1];
        }
    }

    private void ensureShadowGradient(float bw, float bh) {
        int keyW = Math.round(bw), keyH = Math.round(bh);
        if (shadowGradient != null && keyW == shadowW && keyH == shadowH) return;
        shadowW = keyW;
        shadowH = keyH;
        float r = Math.max(bw * 0.34f, dp(6f));
        shadowGradient = new RadialGradient(0f, 0f, r,
                new int[]{0x40000000, 0x00000000}, new float[]{0.15f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                dragging = false;
                clickFired = false;
                longPressFired = false;
                pressScale = 0.94f;
                velocity = 0f;
                totalDx = 0f;
                totalDy = 0f;
                downX = lastX = e.getRawX();
                downY = lastY = e.getRawY();
                downAt = System.currentTimeMillis();

                // 上一颗「单击」还在等双击窗口：这一下按下就说明是双击，别再让菜单自己弹出来。
                if (tapPending) {
                    removeCallbacks(singleTap);
                    tapPending = false;
                    doubleCandidate = true;
                } else {
                    doubleCandidate = false;
                }

                postDelayed(longPress, LONG_PRESS_MS);
                if (listener != null) listener.onWakeFromEdge();
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - lastX;
                float dy = e.getRawY() - lastY;
                velocity = dx;
                totalDx += dx;
                totalDy += dy;
                if (!dragging) {
                    float movedX = Math.abs(e.getRawX() - downX);
                    float movedY = Math.abs(e.getRawY() - downY);
                    if (movedX > dp(6f) || movedY > dp(6f)) {
                        dragging = true;
                        doubleCandidate = false;
                        pressScale = 1f;
                        removeCallbacks(longPress);
                    }
                }
                if (dragging) {
                    tilt = Math.max(-TILT_MAX, Math.min(TILT_MAX, velocity * 0.35f));
                    if (listener != null) listener.onDrag(dx, dy, velocity);
                }
                lastX = e.getRawX();
                lastY = e.getRawY();
                return true;
            }

            case MotionEvent.ACTION_UP: {
                pressed = false;
                pressScale = 1f;
                tilt = 0f;
                removeCallbacks(longPress);
                invalidate();
                long held = System.currentTimeMillis() - downAt;

                if (dragging) {
                    dragging = false;
                    if (longPressFired) return true;
                    if (trySwipe(held)) return true;
                    if (listener != null) listener.onRelease();
                    return true;
                }

                if (longPressFired) {
                    doubleCandidate = false;
                    return true;
                }
                if (clickFired) return true;
                clickFired = true;

                if (doubleCandidate) {
                    doubleCandidate = false;
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.onDoubleTap();
                } else {
                    tapPending = true;
                    postDelayed(singleTap, DOUBLE_MS);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                pressScale = 1f;
                tilt = 0f;
                removeCallbacks(longPress);
                if (dragging && listener != null) listener.onRelease();
                dragging = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /**
     * 甩出去算手势，慢慢拖算摆位置。
     * 上滑开菜单、下滑贴边收好、横滑直接停到那一侧——都比「拖到边上再松手」快。
     */
    private boolean trySwipe(long heldMs) {
        float ax = Math.abs(totalDx), ay = Math.abs(totalDy);
        if (ay > dp(46f) && ay > ax * 1.35f && heldMs < 520) {
            if (listener != null) {
                listener.onSwipe(totalDy < 0 ? Listener.SWIPE_UP : Listener.SWIPE_DOWN);
            }
            return true;
        }
        if (ax > dp(46f) && heldMs < 340 && ax > ay * 1.15f) {
            if (listener != null) {
                listener.onSwipe(totalDx > 0 ? Listener.SWIPE_RIGHT : Listener.SWIPE_LEFT);
            }
            return true;
        }
        return false;
    }
}
