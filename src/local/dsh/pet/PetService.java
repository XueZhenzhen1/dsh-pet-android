package local.dsh.pet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 前台服务：把「鱼球」挂到系统窗口层（TYPE_APPLICATION_OVERLAY），盖在微信、桌面、任何 App 之上。
 *
 * 三条窗口：宠物本体（可点可拖）+ 气泡（不可点，按需出现）+ 功能面板（PetMenu，点一下才出现）。
 *
 * 窗口尺寸 = 立绘 + 四周留白（padX / padY）。留白的意义是让呼吸、弹跳、拖拽倾斜
 * 都在窗口内部完成，立绘任何时候都是完整的，不会被裁掉一截。
 * 贴边停靠只裁留白：窗口收窄、立绘按完整宽度画，所以贴边时鱼也是整条的。
 *
 * 和电脑的同步是双向的：
 *   电脑 → 手机：长轮询拿 say / anim / sleep，手机上的鱼同步说话、睡觉、跟着演动作。
 *   手机 → 电脑：点菜单的动作、表情、余额、天气、插件菜单，全部转发给插件宿主执行。
 */
public class PetService extends Service implements PetView.Listener, PetSync.Listener {

    /** 悬浮球是否在跑：配置页靠它显示状态。 */
    public static volatile boolean RUNNING = false;

    public static final String ACTION_OVERLAY_STATE = "local.dsh.pet.OVERLAY_STATE";
    public static final String EXTRA_ON = "on";

    /** 从 App 里直接下指令（快捷动作），不用先点开菜单。 */
    public static final String EXTRA_DO = "do";

    private static final String CHANNEL_ID = "pet";
    private static final int NOTI_ID = 0x5D5;

    private static final int SIDE_NONE = 0;
    private static final int SIDE_LEFT = 1;
    private static final int SIDE_RIGHT = 2;

    /** 开了「省地方」模式后，贴边只露出这么宽。默认关，鱼永远是整条的。 */
    private static final float PEEK_VISIBLE = 0.5f;

    /** 点「说句话」时先顶上的一句。真正的 AI 台词两三秒后替换它。 */
    private static final String[] LOCAL_WHISPERS = {
            "在呢，说吧。",
            "嗯？我听着。",
            "今天想聊点什么。",
            "刚睡醒，脑子还在加载。",
            "你忙你的，我在这儿待着。"
    };

    private static final String[] TOUCH_LINES = {
            "唔……别摸啦。",
            "摸头会长不高的。",
            "再摸就咬你了。",
            "好啦好啦，在呢。"
    };

    private WindowManager wm;
    private Prefs prefs;
    private PetSync sync;

    private PetView pet;
    private PetBubble bubble;
    private String bubbleTag = "";
    private PetMenu menu;
    private PetMenu activeMenu;
    private WindowManager.LayoutParams lp;
    private WindowManager.LayoutParams lb;

    private int artW, artH, padX, padY, winW, winH;
    private int dockSide = SIDE_NONE;
    private float dockFrac = 0f;
    private boolean peeked;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.HashMap<String, Bitmap> memeCache = new java.util.HashMap<>();

    private boolean offline;
    private boolean sleeping;
    private long lastSayCounter = -1;
    private long lastPokeAt = 0;
    private long lastSyncAt = 0;
    private String lastAnimName = "";
    private long localAnimAt = 0;
    private boolean wasOffline;
    private boolean helloSaid;
    private boolean warmed;
    private String pendingClip = "";
    private JSONArray menuItems;

    private final PetMenu.Host menuHost = new PetMenu.Host() {
        @Override
        public void onMenuAction(String id, String arg) {
            handleMenuAction(id, arg);
        }

        @Override
        public void onMenuDismiss() {
            menu = null;
        }

        @Override
        public boolean isOnline() {
            return !offline;
        }

        @Override
        public boolean isSleeping() {
            return sleeping;
        }

        @Override
        public long lastSyncAt() {
            return lastSyncAt;
        }

        @Override
        public JSONArray extraMenu() {
            return menuItems;
        }
    };

    private final Runnable hideBubbleRun = this::hideBubble;

    private final Runnable rePeekRun = new Runnable() {
        @Override
        public void run() {
            if (dockSide != SIDE_NONE && !peeked && prefs.getPeek()) animateDock(1f, 240);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        PetSync.setCacheDir(getFilesDir());

        artW = dp(78);
        artH = Math.round(artW * 781f / 512f);
        // 留白要够演：摆动 14°、弹跳 18dp、涨大 30% 都在这圈空白里发生，
        // 窗口比立绘小的话，最后那道「容得下才缩放」的兜底会把动作压回去。
        padX = dp(17);
        padY = dp(22);
        winW = artW + padX * 2;
        winH = artH + padY * 2;

        startForegroundSafely();
        showPet();
        setRunning(true);
        prefs.setOverlayDesired(true);
        Keeper.scheduleWatchdog(this);

        sync = new PetSync(prefs, this);
        sync.start();
    }

    private void setRunning(boolean on) {
        RUNNING = on;
        prefs.setOverlayRunning(on);
        try {
            Intent i = new Intent(ACTION_OVERLAY_STATE);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_ON, on);
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (pet == null) showPet();
        setRunning(true);
        prefs.setOverlayDesired(true);
        Keeper.scheduleWatchdog(this);
        // 设置页改了偏好会再叫一次服务：这里把开关同步给正在跑的同步线程
        if (sync != null) sync.setLowPower(prefs.getLowPower());
        final String what = intent == null ? null : intent.getStringExtra(EXTRA_DO);
        if (what != null && !what.isEmpty()) {
            // 让鱼先出来站稳，再接指令——不然气泡会挂在一个还没上屏的窗口旁边
            ui.postDelayed(() -> handleMenuAction(what, intent.getStringExtra("arg")), 260);
        }
        return START_STICKY;
    }

    /* ------------------------- 通知 ------------------------- */

    private void startForegroundSafely() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("鱼球在陪着你")
                .setContentText("点一下出菜单，双击进 dsh；点这里进设置")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private void updateNotification(String note) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("鱼球在陪着你")
                    .setContentText(note)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            nm.notify(NOTI_ID, n);
        } catch (Exception ignored) {
        }
    }

    /* ------------------------- 宠物窗口 ------------------------- */

    private void showPet() {
        int[] screen = screenSize();
        int barTop = barHeight("status_bar_height");
        int barBottom = barHeight("navigation_bar_height");

        int defX = screen[0] - winW - dp(2);
        int defY = Math.max(barTop + dp(40), screen[1] / 2 - winH / 2);

        pet = new PetView(this);
        pet.setListener(this);
        pet.setBaseWidth(artW);
        pet.setArtPadding(padX, padY);

        lp = new WindowManager.LayoutParams(
                winW, winH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampX(prefs.getX(defX), screen[0]);
        lp.y = clampY(prefs.getY(defY), screen[1], barTop, barBottom);

        try {
            wm.addView(pet, lp);
        } catch (Exception e) {
            Toast.makeText(this, "添加悬浮窗失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        String saved = prefs.getDock();
        if ("left".equals(saved)) {
            dockSide = SIDE_LEFT;
            applyDock(0f);
        } else if ("right".equals(saved)) {
            dockSide = SIDE_RIGHT;
            applyDock(0f);
        }

        // 出来的时候蹦一下：让人知道它上线了
        ui.postDelayed(() -> pet.play(PetAction.HOP), 200);
    }

    private int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int barHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int clampX(int x, int screenW) {
        return Math.max(0, Math.min(x, Math.max(0, screenW - winW)));
    }

    private int clampY(int y, int screenH, int barTop, int barBottom) {
        int min = barTop + dp(6);
        int max = screenH - winH - barBottom - dp(6);
        if (max < min) max = Math.max(min, screenH - winH);
        return Math.max(min, Math.min(y, max));
    }

    private void safeUpdate() {
        try {
            if (pet != null && pet.isAttachedToWindow()) wm.updateViewLayout(pet, lp);
        } catch (Exception ignored) {
        }
    }

    /* ------------------------- 贴边停靠 ------------------------- */

    private int peekWidth() {
        return Math.max(dp(34), Math.round(winW * PEEK_VISIBLE));
    }

    /**
     * frac = 0：整条鱼贴在边上；frac = 1：只露出 peekWidth 那一条（省地方模式）。
     * 裁的始终是窗口里的透明留白，鱼本身不会被切。
     */
    private void applyDock(float frac) {
        if (pet == null || lp == null) return;
        dockFrac = frac;
        int[] s = screenSize();
        int full = winW;
        int w = Math.round(full - (full - peekWidth()) * frac);
        lp.width = w;
        lp.height = winH;
        if (dockSide == SIDE_LEFT) {
            lp.x = 0;
            // 立绘按完整宽度排版，靠 clipShift 把窗口右边缘之外的透明留白裁掉
            pet.setClipShift(w - full);
        } else if (dockSide == SIDE_RIGHT) {
            lp.x = Math.max(0, s[0] - w);
            pet.setClipShift(0);
        } else {
            lp.width = winW;
            lp.x = clampX(lp.x, s[0]);
            pet.setClipShift(0);
        }
        safeUpdate();
        placeBubble();
    }

    private void animateDock(float target, int durationMs) {
        ui.removeCallbacks(rePeekRun);
        ValueAnimator a = ValueAnimator.ofFloat(dockFrac, target);
        a.setDuration(durationMs);
        a.addUpdateListener(v -> applyDock((float) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                peeked = target >= 0.99f;
                if (peeked) prefs.setDock(dockSide == SIDE_LEFT ? "left" : "right");
                if (!peeked && dockSide != SIDE_NONE) {
                    prefs.setPos(lp.x, lp.y);
                    if (prefs.getPeek()) ui.postDelayed(rePeekRun, 7000);
                }
            }
        });
        a.start();
    }

    private void undock() {
        ui.removeCallbacks(rePeekRun);
        int side = dockSide;
        int cx = lp.x + lp.width / 2;
        dockSide = SIDE_NONE;
        peeked = false;
        dockFrac = 0f;
        lp.width = winW;
        lp.height = winH;
        pet.setClipShift(0);
        lp.x = clampX(cx - winW / 2, screenSize()[0]);
        if (side == SIDE_LEFT) lp.x = Math.max(lp.x, 0);
        prefs.setDock("");
        safeUpdate();
    }

    /* ------------------------- 气泡 ------------------------- */

    private void showBubble(String text, String memeName, long durationMs) {
        boolean hasText = text != null && !text.isEmpty();
        boolean hasMeme = memeName != null && !memeName.isEmpty();
        if (!hasText && !hasMeme) return;
        if (menu != null && menu.isShowing()) return;
        if (bubble == null) {
            bubble = new PetBubble(this);
            lb = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lb.gravity = Gravity.TOP | Gravity.START;
        }

        bubbleTag = hasMeme ? memeName : "";
        Bitmap img = hasMeme ? memeCache.get(memeName) : null;

        bubble.setContent(text == null ? "" : text, img, true);
        bubble.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        lb.width = bubble.getMeasuredWidth();
        lb.height = bubble.getMeasuredHeight();
        placeBubble();

        try {
            if (!bubble.isAttachedToWindow()) {
                bubble.setAlpha(0f);
                wm.addView(bubble, lb);
            } else {
                wm.updateViewLayout(bubble, lb);
            }
            bubble.animate().cancel();
            bubble.animate().alpha(1f).setDuration(170).start();
        } catch (Exception ignored) {
        }

        ui.removeCallbacks(hideBubbleRun);
        ui.postDelayed(hideBubbleRun, Math.max(3000, durationMs));

        if (hasMeme && img == null && !memeCache.containsKey(memeName)) {
            loadMeme(memeName, text);
        }
    }

    /**
     * 表情图异步取回来再补上。
     *
     * 面板里点一张表情时，气泡是先出文字占位的——图要从电脑上取，
     * 一两兆的东西不该卡住主线程。图回来了如果气泡还在等同一张，就换上去。
     */
    private void loadMeme(String name, String forText) {
        Thread t = new Thread(() -> {
            Bitmap b = PetSync.fetchMeme(prefs, name);
            if (b == null) return;
            final Bitmap rounded = PetBubble.roundCorners(b, Math.round(dp(9f)));
            ui.post(() -> {
                memeCache.put(name, rounded);
                if (bubble != null && name.equals(bubbleTag)) {
                    showBubble(forText == null || forText.isEmpty() ? " " : forText, name, 9000);
                }
            });
        }, "dsh-pet-meme");
        t.setDaemon(true);
        t.start();
    }

    private void placeBubble() {
        if (bubble == null || lb == null || lp == null) return;
        int[] s = screenSize();
        int bw = lb.width, bh = lb.height;
        // 气泡对准立绘中心，而不是窗口中心（贴边时两者会差一点）
        int cx = lp.x + lp.width / 2;
        int x = cx - bw / 2;
        x = Math.max(dp(6), Math.min(x, s[0] - bw - dp(6)));

        int barTop = barHeight("status_bar_height");
        int y = lp.y + padY - bh - dp(2);
        boolean tailDown = true;
        int maxY = s[1] - bh - dp(6);
        if (y < barTop + dp(4)) {
            y = Math.min(lp.y + padY + artH + dp(4), maxY);
            tailDown = false;
        }
        y = Math.max(barTop + dp(2), Math.min(y, maxY));
        lb.x = x;
        lb.y = y;
        bubble.setTailDown(tailDown);
        bubble.setTailAnchor(cx - x);
        try {
            if (bubble.isAttachedToWindow()) wm.updateViewLayout(bubble, lb);
        } catch (Exception ignored) {
        }
    }

    private void hideBubble() {
        if (bubble == null || !bubble.isAttachedToWindow()) return;
        bubble.animate().cancel();
        bubble.animate().alpha(0f).setDuration(140)
                .withEndAction(() -> {
                    try {
                        if (bubble != null && bubble.isAttachedToWindow()) wm.removeView(bubble);
                    } catch (Exception ignored) {
                    }
                }).start();
    }

    /* ------------------------- PetView.Listener ------------------------- */

    @Override
    public void onDrag(float dx, float dy, float vx) {
        hideBubble();
        ui.removeCallbacks(rePeekRun);
        if (dockSide != SIDE_NONE) undock();

        int[] s = screenSize();
        lp.x = clampX(Math.round(lp.x + dx), s[0]);
        lp.y = clampY(Math.round(lp.y + dy), s[1],
                barHeight("status_bar_height"), barHeight("navigation_bar_height"));
        safeUpdate();
    }

    @Override
    public void onRelease() {
        int[] s = screenSize();
        int center = lp.x + winW / 2;
        dockSide = center < s[0] / 2 ? SIDE_LEFT : SIDE_RIGHT;

        ValueAnimator a = ValueAnimator.ofInt(lp.x,
                dockSide == SIDE_LEFT ? 0 : s[0] - winW);
        a.setDuration(190);
        a.addUpdateListener(v -> {
            lp.x = (int) v.getAnimatedValue();
            safeUpdate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                prefs.setPos(lp.x, lp.y);
                prefs.setDock(dockSide == SIDE_LEFT ? "left" : "right");
                if (prefs.getPeek()) {
                    ui.postDelayed(() -> animateDock(1f, 260), 850);
                }
            }
        });
        a.start();
    }

    @Override
    public void onTap() {
        if (menu != null && menu.isShowing()) {
            menu.dismiss();
            return;
        }
        pet.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        openMenu();
    }

    @Override
    public void onDoubleTap() {
        dismissMenus();
        pet.hop();
        pokeDesktop("点击回应-元气挥手");
        openWeb();
    }

    @Override
    public void onSwipe(int dir) {
        if (dir == PetView.Listener.SWIPE_UP) {
            openMenu();
            return;
        }
        if (dir == PetView.Listener.SWIPE_DOWN) {
            int side = dockSide != SIDE_NONE ? dockSide : nearestSide();
            snapTo(side, prefs.getPeek());
            return;
        }
        snapTo(dir == PetView.Listener.SWIPE_LEFT ? SIDE_LEFT : SIDE_RIGHT, prefs.getPeek());
    }

    @Override
    public void onWakeFromEdge() {
        if (dockSide != SIDE_NONE && dockFrac > 0.05f) {
            animateDock(0f, 190);
        }
    }

    @Override
    public void onLongPressPet() {
        if (menu != null) return;
        hideBubble();
        pet.play(PetAction.SQUASH);
        pokeDesktop("点击回应-害羞惊讶");
        showBubble(TOUCH_LINES[(int) (System.currentTimeMillis() / 1000 % TOUCH_LINES.length)],
                null, 2600);
    }

    private int nearestSide() {
        int[] s = screenSize();
        return (lp.x + winW / 2) < s[0] / 2 ? SIDE_LEFT : SIDE_RIGHT;
    }

    /** 甩到某一侧停好；thenPeek 为 true 且开了省地方模式时，停稳后再收进去。 */
    private void snapTo(int side, boolean thenPeek) {
        if (lp == null) return;
        hideBubble();
        ui.removeCallbacks(rePeekRun);
        dockSide = side;
        dockFrac = 0f;
        peeked = false;
        lp.width = winW;
        lp.height = winH;
        pet.setClipShift(0);

        int[] s = screenSize();
        int target = side == SIDE_LEFT ? 0 : Math.max(0, s[0] - winW);
        ValueAnimator a = ValueAnimator.ofInt(lp.x, target);
        a.setDuration(200);
        a.addUpdateListener(v -> {
            lp.x = (int) v.getAnimatedValue();
            safeUpdate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                prefs.setPos(lp.x, lp.y);
                prefs.setDock(dockSide == SIDE_LEFT ? "left" : "right");
                if (thenPeek) {
                    ui.postDelayed(() -> animateDock(1f, 240), 700);
                }
            }
        });
        a.start();
    }

    /* ------------------------- 功能面板 ------------------------- */

    private void openMenu() {
        if (activeMenu != null && activeMenu.isShowing()) return;
        hideBubble();
        // 贴边收着一半时先把整条鱼放出来，面板才知道该贴哪边摆。
        if (dockSide != SIDE_NONE && dockFrac > 0.05f) {
            ui.removeCallbacks(rePeekRun);
            applyDock(0f);
            peeked = false;
        }
        menu = new PetMenu(this, wm, prefs, menuHost);
        activeMenu = menu;
        menu.show(new int[]{lp.x, lp.y, lp.x + lp.width, lp.y + winH});
    }

    /**
     * 收掉面板。
     * 面板会自己播退场动画，但服务要停的时候必须立刻摘窗口——
     * 否则窗口没主人了，会留一张半透明的白卡片盖在别人桌上。
     */
    private void dismissMenus() {
        if (activeMenu != null) {
            activeMenu.dismissImmediate();
            activeMenu = null;
        }
        menu = null;
    }

    private void handleMenuAction(String id, String arg) {
        if (id == null) return;
        switch (id) {
            case "whisper":
                if (!requireOnline()) return;
                // 先张嘴说一句本地的。AI 那句要两三秒，之前这段时间气泡写的是
                // 「让我想一句……」，看起来像卡住；先出声再换词，主观上就是「秒回」。
                pet.play(PetAction.POP);
                showBubble(LOCAL_WHISPERS[(int) (System.currentTimeMillis() / 997 % LOCAL_WHISPERS.length)],
                        null, 2600);
                sync.whisper(null);
                break;

            case "meme":
                if (!requireOnline()) {
                    pet.play(PetAction.HOP);
                    return;
                }
                // 表情图在本地缓存里就直接出图（启动时会预取），只有第一次才等网络。
                // 之前这里固定先显示「正在想台词…」纯文字，用户要盯着空白好几秒。
                pet.play(PetAction.HOP);
                showBubble("", arg, 12000);   // 有缓存就这一帧出图，冷启动才等网络
                sync.whisper(arg);
                break;

            case "anim": {
                // 先让本机演出立刻起手：素材要解码、要下载，但「点下去马上有动静」不能等。
                int preset = PetAction.presetFor(arg);
                pet.play(preset);
                localAnimAt = System.currentTimeMillis();
                if (!offline) sync.playAnim(arg);
                showBubble("「" + arg + "」", null, 2600);
                // 再把和电脑同一段动画盖上去（有缓存就是下一帧的事）
                playClipAsync(arg);
                break;
            }

            case "balance":
                if (!requireOnline()) return;
                pet.play(PetAction.POP);
                sync.showBalance();
                showBubble("翻一下钱包……", null, 9000);
                // /balance 只把结果写进 sections.balance，不写 say，所以这边自己回读一次
                ui.postDelayed(() -> readBackResult(true, 0), 1300);
                break;

            case "environment":
                if (!requireOnline()) return;
                pet.play(PetAction.STRETCH);
                sync.showEnvironment();
                showBubble("看看今天什么天……", null, 12000);
                ui.postDelayed(() -> readBackResult(false, 0), 1600);
                break;

            case "sleep":
            case "wake":
                if (!requireOnline()) return;
                sync.setSleeping("sleep".equals(id));
                pet.play("sleep".equals(id) ? PetAction.DOZE : PetAction.HOP);
                break;

            case "plugin":
                if (!requireOnline()) return;
                if (arg != null && arg.contains("\u0000")) {
                    String[] parts = arg.split("\u0000", 2);
                    sync.clickMenu(parts[0], parts[1]);
                    pet.play(PetAction.HOP);
                    showBubble("点了「" + parts[1] + "」", null, 4000);
                }
                break;

            case "reconnect":
                if (sync != null) sync.stop();
                sync = new PetSync(prefs, this);
                sync.start();
                showBubble("重新连一下……", null, 5000);
                break;

            case "open":
                openWeb();
                break;

            case "settings":
                Intent cfg = new Intent(this, MainActivity.class);
                cfg.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(cfg);
                break;

            case "home":
                resetHome();
                break;

            case "hide":
                // 先把面板窗口摘干净，再停服务：顺序反了会留下一个没主的遮罩
                dismissMenus();
                prefs.setOverlayDesired(false);
                Keeper.cancelWatchdog(this);
                ui.postDelayed(() -> {
                    Toast.makeText(this, "收好了。电脑上那只不受影响。", Toast.LENGTH_SHORT).show();
                    stopSelf();
                }, 160);
                break;

            default:
                break;
        }
    }

    /**
     * 余额 / 天气的回读：这两个动作在插件里只更新 sections，不会产生气泡，
     * 手机端必须自己再取一次快照，否则用户只会看到「翻一下钱包……」然后没有下文。
     *
     * 失败重试两次就停：网络慢的时候催太紧只会堆请求，而气泡里已经说了「连不上」。
     */
    /**
     * 把和电脑同一段动作的动画盖到立绘上。
     *
     * 有缓存：解码十几毫秒，紧接着就播；
     * 没缓存：后台下载，到了再播（这时程序化演出已经在演了，不会出现空白等待）。
     */
    private void playClipAsync(final String name) {
        if (name == null || name.isEmpty()) return;
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        if (!prefs.getRealAnim()) return;      // 关掉就只用程序化演出
        pendingClip = name;
        AnimLib.play(getApplicationContext(), prefs, name, (n, d) -> {
            if (d == null) return;
            // 期间用户又点了别的动作：这份结果已经过期，丢掉
            if (!name.equals(pendingClip)) return;
            if (pet != null) pet.playClip(name, d);
        });
    }

    /**
     * 预热本地缓存：配置（动作清单）+ 27 张表情图。
     * 连上电脑后台慢慢搬，搬完以后打开面板、点表情都是本机读文件。
     */
    private void warmUpCache() {
        if (warmed) return;
        warmed = true;
        Thread t = new Thread(() -> {
            JSONObject cfg = PetSync.cachedConfig();
            if (cfg == null) cfg = PetSync.fetchConfig(prefs);
            if (cfg == null) return;
            JSONObject main = cfg.optJSONObject("main");
            JSONObject memes = main == null ? null : main.optJSONObject("memes");
            if (memes == null) return;
            java.util.Iterator<String> it = memes.keys();
            while (it.hasNext()) {
                if (!RUNNING) return;
                PetSync.prefetchMeme(prefs, it.next());
            }
        }, "dsh-pet-warmup");
        t.setDaemon(true);
        t.start();
    }

    private void readBackResult(boolean balance, int attempt) {
        Thread t = new Thread(() -> {
            PetSync.State s = PetSync.fetchSnapshot(prefs);
            String text = s == null ? "" : (balance ? s.balanceText : s.envText);
            ui.post(() -> {
                if (!text.isEmpty()) {
                    showBubble(text, null, 9000);
                } else if (attempt < 2) {
                    ui.postDelayed(() -> readBackResult(balance, attempt + 1), 2200);
                } else {
                    showBubble(balance ? "钱包那边没回话，等会儿再试。" : "天气没取到，等会儿再试。",
                            null, 5000);
                }
            });
        }, "dsh-pet-readback");
        t.setDaemon(true);
        t.start();
    }

    private boolean requireOnline() {
        if (!offline) return true;
        pet.play(PetAction.WOBBLE);
        showBubble("连不上电脑。先看 Tailscale 和 dsh 开着没。", null, 5200);
        return false;
    }

    private void openWeb() {
        Intent i = new Intent(this, WebActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    private void resetHome() {
        dismissMenus();
        hideBubble();
        ui.removeCallbacks(rePeekRun);
        int[] s = screenSize();
        int barTop = barHeight("status_bar_height");
        dockSide = SIDE_NONE;
        dockFrac = 0f;
        peeked = false;
        lp.width = winW;
        lp.height = winH;
        pet.setClipShift(0);
        lp.x = Math.max(0, s[0] - winW - dp(2));
        lp.y = Math.max(barTop + dp(40), s[1] / 2 - winH / 2);
        prefs.setDock("");
        prefs.setPos(lp.x, lp.y);
        safeUpdate();
        pet.play(PetAction.HOP);
        showBubble("回来了。", null, 2200);
    }

    /** 手机上的鱼被点了 → 电脑上的同一只也挥手（双向同步）。节流 2.5 秒。 */
    private void pokeDesktop(String anim) {
        long now = System.currentTimeMillis();
        if (now - lastPokeAt < 2500) return;
        lastPokeAt = now;
        if (sync != null) sync.pokeAnim(anim);
    }

    /* ------------------------- PetSync.Listener ------------------------- */

    @Override
    public void onPetState(PetSync.State s) {
        lastSyncAt = System.currentTimeMillis();
        if (s.menu != null) menuItems = s.menu;

        boolean sleepy = s.sleeping || (s.anim != null && s.anim.contains("睡"));
        boolean working = "thinking".equals(s.work) || "working".equals(s.work)
                || "waiting".equals(s.work);
        sleeping = sleepy;
        if (pet != null) pet.setPetState(sleepy, working);

        if (s.hasSay && s.sayCounter != lastSayCounter) {
            lastSayCounter = s.sayCounter;
            long d = s.duration > 0 ? s.duration : 8000;
            showBubble(s.text, s.image, d);
        }

        // 电脑上在演什么，手机上那只跟着演什么。
        // 自己刚点过的动作在两秒内不重复演——那边回传同一个名字，会变成抖两下。
        if (pet != null && s.anim != null && !s.anim.isEmpty()
                && !s.anim.equals(lastAnimName)) {
            lastAnimName = s.anim;
            // 待机、转向、被拖拽这些是电脑那边的底噪，手机上跟着弹会显得神经质
            boolean ambient = s.anim.contains("待机") || s.anim.contains("呼吸")
                    || s.anim.contains("转向") || s.anim.contains("拖拽")
                    || s.anim.contains("鼠标");
            if (!ambient && System.currentTimeMillis() - localAnimAt > 1500) {
                pet.play(PetAction.presetFor(s.anim));
                playClipAsync(s.anim);
            }
        }

        if (!helloSaid) {
            helloSaid = true;
            if (pet != null) pet.play(PetAction.POP);
        }
    }

    @Override
    public void onStatus(boolean online, String note) {
        offline = !online;
        if (online) {
            warmUpCache();
            if (wasOffline) {
                updateNotification("已和电脑同步");
                wasOffline = false;
            }
            return;
        }
        if (!wasOffline) {
            wasOffline = true;
            updateNotification("连不上电脑，正在重试（检查 Tailscale / dsh 是否在跑）");
        }
    }

    /* ------------------------- 生命周期 ------------------------- */

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 面板的位置是按旧屏幕算的，转屏后直接收掉，下一次点开重新贴。
        dismissMenus();
        if (lp == null) return;
        int[] s = screenSize();
        if (dockSide != SIDE_NONE) {
            applyDock(dockFrac);
        } else {
            lp.x = clampX(lp.x, s[0]);
            lp.y = clampY(lp.y, s[1], barHeight("status_bar_height"),
                    barHeight("navigation_bar_height"));
            safeUpdate();
        }
    }

    /**
     * 用户从最近任务里划掉 App —— 这是 MIUI / EMUI 最常杀悬浮窗的时机。
     * 排一次 1 秒后的广播把自己叫回来；用户真的点了「收起」时 desired 已置 false，
     * 那次广播会什么都不做。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (prefs.isOverlayDesired()) {
            try {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                Intent i = new Intent(this, BootReceiver.class);
                i.setAction("local.dsh.pet.REVIVE");
                int flags = android.os.Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT;
                PendingIntent pi = PendingIntent.getBroadcast(this, 0x5D5F, i, flags);
                if (am != null) {
                    am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pi);
                }
            } catch (Throwable ignored) {
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        setRunning(false);
        ui.removeCallbacksAndMessages(null);
        dismissMenus();
        if (sync != null) sync.stop();
        if (bubble != null) {
            try {
                if (bubble.isAttachedToWindow()) wm.removeView(bubble);
            } catch (Exception ignored) {
            }
            bubble = null;
        }
        if (pet != null) {
            try {
                wm.removeView(pet);
            } catch (Exception ignored) {
            }
            pet = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
