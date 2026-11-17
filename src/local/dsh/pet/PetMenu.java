package local.dsh.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 系统悬浮球的功能面板。
 *
 * 结构上分三层，和电脑上右键菜单是同一套语义：
 *   ① 顶栏：谁在这、通没通、多久前同步过
 *   ② 宫格：八个一次就能做完的动作（说话 / 表情 / 动作 / 余额 / 天气 / 睡觉 / dsh / 设置）
 *   ③ 列表：低频但必须有的（回到初始位置、收起、插件注册的自定义项）
 *
 * 二级页（表情 / 动作）在同一个卡片里换内容，不叠新窗口——
 * 返回键退的是「页」，不是「窗口」，这样从菜单进二级页再返回，鱼还在原处，不会闪。
 *
 * 卡片贴哪一侧由鱼的位置决定：鱼在左半屏就摆右边，反之摆左边；上面放不下就翻到下面。
 */
public class PetMenu extends FrameLayout {

    public interface Host {
        /** id：whisper / meme / anim / balance / environment / sleep / wake / open / settings
         *      / home / hide / reconnect / plugin */
        void onMenuAction(String id, String arg);

        void onMenuDismiss();

        boolean isOnline();

        boolean isSleeping();

        /** 上次同步成功的时刻（毫秒），0 表示还没成功过。 */
        long lastSyncAt();

        /** 插件注册的自定义菜单项，可能为 null。 */
        JSONArray extraMenu();
    }

    private static final int MAX_MENU_MS = 45000;
    private static final long CONFIG_TTL_MS = 5 * 60 * 1000L;

    private static JSONObject configCache;
    private static long configAt;

    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "dsh-pet-menu");
        t.setDaemon(true);
        return t;
    });

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final Host host;
    private final float density;

    private final LinearLayout card;
    private final LinearLayout quickGrid;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final TextView title;
    private final LinearLayout chipBox;
    private final View headAvatar;
    private final View headBack;

    private WindowManager.LayoutParams lp;
    private int screenW, screenH, barTop, barBottom, cardW, maxH;
    private int[] petRect = new int[]{0, 0, 0, 0};
    private boolean attached;
    private String section = "main";
    private String animTab = "";

    private final Runnable autoClose = this::dismiss;

    public PetMenu(Context ctx, WindowManager wm, Prefs prefs, Host host) {
        super(ctx);
        this.ctx = ctx;
        this.wm = wm;
        this.prefs = prefs;
        this.host = host;
        this.density = ctx.getResources().getDisplayMetrics().density;

        setFocusable(true);
        setFocusableInTouchMode(true);

        // 透明遮罩：点空白 = 关面板，同时挡住后面那个 App 的误触。变暗交给窗口 dim。
        View scrim = new View(ctx);
        scrim.setBackgroundColor(Color.TRANSPARENT);
        scrim.setOnClickListener(v -> dismiss());
        addView(scrim, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = Ui.column(ctx);
        card.setBackground(Ui.round(0xFFFFFFFF, 24, ctx, 0.8f, 0x14000000));
        card.setElevation(dp(20));
        card.setClipToOutline(true);
        addView(card, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------------- 顶栏 ---------------- */
        LinearLayout head = Ui.row(ctx);
        head.setPadding(dp(14), dp(12), dp(10), dp(10));

        // 左侧这个位置是「身份 / 返回」二选一：主页面是头像，二级页变成返回箭头
        FrameLayout iconBox = new FrameLayout(ctx);
        ImageView avatar = new ImageView(ctx);
        avatar.setImageResource(R.drawable.ic_avatar);
        avatar.setBackground(Ui.round(0xFFEAF3FF, 999, ctx));
        avatar.setClipToOutline(true);
        iconBox.addView(avatar, new FrameLayout.LayoutParams(dp(38), dp(38)));
        headAvatar = avatar;

        FrameLayout backBox = new FrameLayout(ctx);
        backBox.setBackground(Ui.pressable(ctx, 0xFFF1F5FB, 0xFFE4EBF6, 999));
        backBox.addView(new Glyph(ctx, Glyph.BACK, Ui.BRAND),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        backBox.setClickable(true);
        backBox.setOnClickListener(v -> buildMain());
        backBox.setVisibility(View.GONE);
        iconBox.addView(backBox, new FrameLayout.LayoutParams(dp(38), dp(38)));
        headBack = backBox;

        head.addView(iconBox, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout titles = Ui.column(ctx);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(11);
        head.addView(titles, tl);

        title = Ui.bold(ctx, "鱼球", 16f, Ui.INK);
        titles.addView(title);

        chipBox = Ui.row(ctx);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(4);
        titles.addView(chipBox, cp);

        TextView close = Ui.text(ctx, "×", 17f, Ui.INK3);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.pressable(ctx, 0xFFF2F5FA, 0xFFE6ECF5, 999));
        close.setOnClickListener(v -> dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));

        Ui.fill(card, head);
        card.addView(Ui.hairline(ctx, 14));

        /* ---------------- 宫格 ---------------- */
        quickGrid = Ui.column(ctx);
        quickGrid.setPadding(dp(6), dp(8), dp(6), dp(2));
        Ui.fill(card, quickGrid);

        /* ---------------- 滚动区 ---------------- */
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = Ui.column(ctx);
        body.setPadding(0, dp(2), 0, dp(4));
        scroll.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /* ============================ 显示 / 关闭 ============================ */

    public void show(int[] rect) {
        if (attached) return;
        this.petRect = rect;

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        barTop = Ui.bar(ctx, "status_bar_height");
        barBottom = Ui.bar(ctx, "navigation_bar_height");
        cardW = Math.min(dp(310), screenW - dp(24));
        maxH = Math.min(Math.round(screenH * 0.74f), screenH - barTop - barBottom - dp(20));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.dimAmount = 0.28f;

        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);

        try {
            wm.addView(this, lp);
            attached = true;
            requestFocus();
            buildMain();
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(160).start();
            postDelayed(autoClose, MAX_MENU_MS);
        } catch (Exception e) {
            attached = false;
            host.onMenuDismiss();
        }
    }

    public boolean isShowing() {
        return attached && isAttachedToWindow();
    }

    public void dismiss() {
        if (!attached) return;
        attached = false;
        removeCallbacks(autoClose);
        try {
            card.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(110)
                    .withEndAction(() -> {
                        try {
                            if (isAttachedToWindow()) wm.removeViewImmediate(this);
                        } catch (Exception ignored) {
                        }
                    }).start();
        } catch (Exception ignored) {
            try {
                wm.removeViewImmediate(this);
            } catch (Exception ignored2) {
            }
        }
        host.onMenuDismiss();
    }

    /** 服务要死了就别播退场动画，直接摘窗口，免得留下一个没主人的悬浮层。 */
    public void dismissImmediate() {
        if (!attached) return;
        attached = false;
        removeCallbacks(autoClose);
        try {
            wm.removeViewImmediate(this);
        } catch (Exception ignored) {
        }
        host.onMenuDismiss();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (attached) {
            removeCallbacks(autoClose);
            postDelayed(autoClose, MAX_MENU_MS);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if ("main".equals(section)) dismiss();
                else buildMain();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /* ============================ 主页面 ============================ */

    private void buildMain() {
        if (!attached) return;
        section = "main";
        setBackMode(false);
        title.setText(host.isSleeping() ? "鱼球（睡了）" : "鱼球");
        renderChip();
        quickGrid.setVisibility(View.VISIBLE);

        quickGrid.removeAllViews();
        Ui.fill(quickGrid, tileRow(
                new T[]{
                        new T(Glyph.CHAT, "说句话", () -> fire("whisper", null)),
                        new T(Glyph.SMILE, "表情", () -> buildMemes()),
                        new T(Glyph.PLAY, "动作", () -> buildActions("")),
                        new T(Glyph.WALLET, "余额", () -> fire("balance", null)),
                }));
        Ui.fill(quickGrid, tileRow(
                new T[]{
                        new T(Glyph.SUN, "天气", () -> fire("environment", null)),
                        new T(host.isSleeping() ? Glyph.SUN : Glyph.MOON,
                                host.isSleeping() ? "叫醒" : "睡觉",
                                () -> fire(host.isSleeping() ? "wake" : "sleep", null)),
                        new T(Glyph.GLOBE, "进 dsh", () -> fire("open", null)),
                        new T(Glyph.GEAR, "设置", () -> fire("settings", null)),
                }));

        body.removeAllViews();

        JSONArray extra = host.extraMenu();
        if (extra != null && extra.length() > 0) {
            Ui.fill(body, groupLabel("它自己带的"));
            for (int i = 0; i < extra.length(); i++) {
                JSONObject o = extra.optJSONObject(i);
                if (o == null) continue;
                final String owner = o.optString("owner", "");
                final String label = o.optString("label", "");
                if (label.isEmpty()) continue;
                Ui.fill(body, Ui.listRow(ctx, Glyph.GRID, label, "插件动作", () -> {
                    dismiss();
                    host.onMenuAction("plugin", owner + "\u0000" + label);
                }));
            }
        }

        Ui.fill(body, groupLabel("摆弄它"));
        Ui.fill(body, Ui.listRow(ctx, Glyph.HOME, "回到初始位置", "贴在右边中间", () -> fire("home", null)));
        if (!host.isOnline()) {
            Ui.fill(body, Ui.listRow(ctx, Glyph.POWER, "重新连接", "再试一次 8790",
                    () -> fire("reconnect", null)));
        }
        Ui.fill(body, Ui.listRow(ctx, Glyph.MINUS, "收起悬浮球", "电脑上那只不受影响", () -> fire("hide", null)));

        Ui.fill(body, footer(host.isOnline()
                ? "双击鱼球直接进 dsh · 长按是摸摸"
                : "连不上电脑时，动作和表情只在手机上演出"));
        refreshLayout();
    }

    private static class T {
        int glyph;
        String label;
        Runnable run;

        T(int g, String l, Runnable r) {
            glyph = g;
            label = l;
            run = r;
        }
    }

    private View tileRow(T[] items) {
        LinearLayout row = Ui.row(ctx);
        row.setPadding(0, 0, 0, 0);
        for (T t : items) {
            row.addView(Ui.tile(ctx, t.glyph, t.label, t.run),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private void renderChip() {
        chipBox.removeAllViews();
        boolean on = host.isOnline();
        String text;
        int color;
        if (!on) {
            text = "连不上电脑";
            color = Ui.BAD;
        } else if (host.isSleeping()) {
            text = "睡着中";
            color = Ui.WARN;
        } else {
            text = "已连接" + ago();
            color = Ui.OK;
        }
        chipBox.addView(Ui.chip(ctx, text, color, color,
                (color & 0x00FFFFFF) | 0x14000000));
    }

    private String ago() {
        long t = host.lastSyncAt();
        if (t <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 5) return " · 刚同步";
        if (s < 60) return " · " + s + " 秒前";
        if (s < 3600) return " · " + (s / 60) + " 分钟前";
        return " · " + (s / 3600) + " 小时前";
    }

    /* ============================ 表情 ============================ */

    private void buildMemes() {
        if (!attached) return;
        section = "memes";
        setBackMode(true);
        title.setText("配个表情");
        renderChip();
        quickGrid.setVisibility(View.GONE);
        body.removeAllViews();
        Ui.fill(body, padNote("正在读取表情清单…"));
        refreshLayout();

        POOL.execute(() -> {
            JSONObject cfg = loadConfig();
            post(() -> {
                if (!attached || !"memes".equals(section)) return;
                fillMemes(cfg);
            });
        });
    }

    private void fillMemes(JSONObject cfg) {
        body.removeAllViews();
        JSONObject main = cfg == null ? null : cfg.optJSONObject("main");
        JSONObject memes = main == null ? null : main.optJSONObject("memes");
        if (memes == null || memes.length() == 0) {
            Ui.fill(body, padNote(cfg == null
                    ? "连不上电脑，拿不到表情清单。先确认 Tailscale 和 dsh 在跑。"
                    : "插件里没配可用的表情包。"));
            refreshLayout();
            return;
        }

        Ui.fill(body, padNote("点一张，手机上先把图亮出来，电脑那边同时配一句话。"));

        JSONArray keys = memes.names();
        List<String> list = new ArrayList<>();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String k = keys.optString(i, "");
                if (!k.isEmpty()) list.add(k);
            }
        }

        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.row(ctx);
                row.setPadding(dp(8), dp(2), dp(8), dp(2));
                Ui.fill(body, row);
            }
            row.addView(memeCell(list.get(i)), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        Ui.fill(body, footer("共 " + list.size() + " 张。原图在电脑上，手机按需取。"));

        // 图从电脑上一张张来，不阻塞面板
        for (String name : list) loadThumb(name);
        refreshLayout();
    }

    private View memeCell(String name) {
        LinearLayout cell = Ui.column(ctx);
        cell.setPadding(dp(4), dp(6), dp(4), dp(6));
        cell.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(Ui.round(0xFFF1F6FE, 14, ctx));
        box.setClipToOutline(true);

        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setTag(name);
        box.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Bitmap cached = THUMBS.get(name);
        if (cached != null) iv.setImageBitmap(cached);

        cell.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        TextView label = Ui.text(ctx, name, 9.5f, Ui.INK2);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(5);
        cell.addView(label, lp2);

        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            fire("meme", name);
        });
        return cell;
    }

    private void loadThumb(String name) {
        if (THUMBS.get(name) != null) return;
        POOL.execute(() -> {
            Bitmap b = PetSync.fetchMeme(prefs, name, dp(120));
            if (b == null) return;
            THUMBS.put(name, b);
            post(() -> {
                if (!attached || !"memes".equals(section)) return;
                View v = findViewWithTagRecursive(this, name);
                if (v instanceof ImageView) {
                    ((ImageView) v).setImageBitmap(b);
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    private static View findViewWithTagRecursive(View root, String tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findViewWithTagRecursive(g.getChildAt(i), tag);
                if (r != null) return r;
            }
        }
        return null;
    }

    /* ============================ 动作 ============================ */

    private void buildActions(String tab) {
        if (!attached) return;
        section = "actions";
        animTab = tab;
        setBackMode(true);
        title.setText("演个动作");
        renderChip();
        quickGrid.setVisibility(View.GONE);
        body.removeAllViews();
        Ui.fill(body, padNote("正在读取动作清单…"));
        refreshLayout();

        POOL.execute(() -> {
            JSONObject cfg = loadConfig();
            post(() -> {
                if (!attached || !"actions".equals(section)) return;
                fillActions(cfg);
            });
        });
    }

    private void fillActions(JSONObject cfg) {
        body.removeAllViews();
        JSONObject main = cfg == null ? null : cfg.optJSONObject("main");
        JSONObject anims = main == null ? null : main.optJSONObject("animations");
        if (anims == null) {
            Ui.fill(body, padNote(cfg == null
                    ? "连不上电脑，动作清单读不到。先确认 Tailscale 和 dsh 在跑。"
                    : "配置里没有 animations，没动作可点。"));
            refreshLayout();
            return;
        }

        // 分类：categories 是插件里真正的「动作池」，moves 是移动类
        List<String> tabs = new ArrayList<>();
        List<List<String>> groups = new ArrayList<>();
        JSONArray cats = anims.optJSONArray("categories");
        if (cats != null) {
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("id", "");
                JSONArray arr = c.optJSONArray("actions");
                if (id.isEmpty() || arr == null || arr.length() == 0) continue;
                tabs.add(id);
                groups.add(toList(arr));
            }
        }
        JSONObject moves = anims.optJSONObject("moves");
        JSONArray moveArr = moves == null ? null : moves.optJSONArray("actions");
        if (moveArr != null && moveArr.length() > 0) {
            List<String> ms = new ArrayList<>();
            for (int i = 0; i < moveArr.length(); i++) {
                JSONObject o = moveArr.optJSONObject(i);
                String n = o == null ? "" : o.optString("name", "");
                if (!n.isEmpty()) ms.add(n);
            }
            if (!ms.isEmpty()) {
                tabs.add("走动");
                groups.add(ms);
            }
        }
        // 点击回应也放进来：这是最常被点的几个
        JSONArray clicks = anims.optJSONArray("clicks");
        if (clicks != null && clicks.length() > 0) {
            tabs.add("点它一下");
            groups.add(toList(clicks));
        }

        if (tabs.isEmpty()) {
            Ui.fill(body, padNote("配置里的动作池是空的。"));
            refreshLayout();
            return;
        }

        int idx = tabs.indexOf(animTab);
        if (idx < 0) idx = 0;

        // 分类胶囊
        LinearLayout chips = Ui.row(ctx);
        chips.setPadding(dp(12), dp(2), dp(12), dp(6));
        ScrollView chipScroll = new ScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setVerticalScrollBarEnabled(false);
        chipScroll.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < tabs.size(); i++) {
            final String t = tabs.get(i);
            boolean on = i == idx;
            TextView c = Ui.text(ctx, t, 12f, on ? 0xFFFFFFFF : Ui.INK2);
            c.setTypeface(android.graphics.Typeface.create("sans-serif",
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            c.setBackground(on ? Ui.gradient(new int[]{Ui.BRAND_MID, Ui.BRAND}, 999, ctx)
                    : Ui.round(0xFFF1F5FB, 999, ctx));
            c.setPadding(dp(12), dp(7), dp(12), dp(7));
            c.setClickable(true);
            c.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                buildActions(t);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = dp(7);
            chips.addView(c, cp);
        }
        Ui.fill(body, chipScroll);

        List<String> list = groups.get(idx);
        // 顺手把这一页前几段真实动画拉到本地：等用户点下去时已经是本机文件。
        // 注意别写成 host instanceof PetService —— host 是服务里的匿名内部类，永远判不中，
        // 之前就是这么静默失效的。
        AnimLib.prefetch(ctx, prefs, list, 6);
        LinearLayout wrap = Ui.column(ctx);
        wrap.setPadding(dp(8), dp(2), dp(8), 0);
        for (String name : list) {
            wrap.addView(animRow(name));
        }
        Ui.fill(body, wrap);
        Ui.fill(body, footer("共 " + list.size() + " 个。手机上播的是电脑上同一段动画，第一次点会先下载，之后是本机文件。"));
        refreshLayout();
    }

    private static List<String> toList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private View animRow(String name) {
        LinearLayout r = Ui.row(ctx);
        r.setPadding(dp(10), dp(11), dp(10), dp(11));
        r.setBackground(Ui.pressable(ctx, Color.TRANSPARENT, 0x0F0B62D8, 12));

        TextView t = Ui.text(ctx, name, 13.5f, Ui.INK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 8.0 以上播的是电脑上同一段动画；更老的系统没有 AnimatedImageDrawable，
        // 那边仍然用本机变形，这里如实标出来，别让人以为点了没反应。
        String hintText = android.os.Build.VERSION.SDK_INT >= 28
                ? "真实动画" : PetAction.label(PetAction.presetFor(name));
        TextView hint = Ui.text(ctx, hintText, 10f, Ui.INK4);
        hint.setSingleLine(true);
        r.addView(hint);

        Glyph play = new Glyph(ctx, Glyph.PLAY, Ui.INK4);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(14), dp(14));
        pp.leftMargin = dp(8);
        r.addView(play, pp);

        r.setClickable(true);
        r.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            fire("anim", name);
        });
        return r;
    }

    /* ============================ 配置 ============================ */

    /**
     * 配置优先走本地缓存：内存 → 磁盘 → 网络。
     *
     * 动作清单和表情清单是「打开面板立刻要」的东西，等网络那一下就是用户感觉到的卡。
     * 拿到缓存的同时后台再刷一次，下次打开就是新的。
     */
    private JSONObject loadConfig() {
        long now = System.currentTimeMillis();
        if (configCache != null && now - configAt < CONFIG_TTL_MS) return configCache;

        JSONObject cached = PetSync.cachedConfig();
        if (cached != null) {
            configCache = cached;
            configAt = now;
            // 后台刷新，不挡这次渲染
            POOL.execute(() -> {
                JSONObject fresh = PetSync.fetchConfig(prefs);
                if (fresh != null) {
                    configCache = fresh;
                    configAt = System.currentTimeMillis();
                }
            });
            return cached;
        }

        JSONObject o = PetSync.fetchConfig(prefs);
        if (o != null) {
            configCache = o;
            configAt = now;
            return o;
        }
        return configCache;
    }

    /* ============================ 小零件 ============================ */

    private View groupLabel(String text) {
        TextView t = Ui.text(ctx, text, 10.5f, Ui.INK3,
                android.graphics.Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(14), dp(12), dp(14), dp(4));
        return t;
    }

    private View padNote(String text) {
        TextView t = Ui.text(ctx, text, 12f, Ui.INK2);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(14), dp(10), dp(14), dp(12));
        return t;
    }

    private View footer(String text) {
        TextView t = Ui.text(ctx, text, 10f, Ui.INK4);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        return t;
    }

    /** 二级页左上角在头像和返回箭头之间切换。 */
    private void setBackMode(boolean on) {
        if (headAvatar != null) headAvatar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (headBack != null) headBack.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /* ============================ 布局 ============================ */

    private void refreshLayout() {
        if (cardW <= 0) return;
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int w = card.getMeasuredWidth();
        int h = card.getMeasuredHeight();

        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (p == null) {
            p = new FrameLayout.LayoutParams(w, h);
            card.setLayoutParams(p);
        }
        p.width = w;
        p.height = h;

        int margin = dp(10);
        int cx = (petRect[0] + petRect[2]) / 2;
        int x = cx < screenW / 2 ? petRect[2] + dp(8) : petRect[0] - w - dp(8);
        if (x < margin) x = margin;
        if (x + w > screenW - margin) x = screenW - w - margin;

        int y = petRect[1] - h - dp(8);
        if (y < barTop + dp(8)) y = petRect[3] + dp(8);
        int maxY = screenH - barBottom - margin - h;
        if (y > maxY) y = maxY;
        if (y < barTop + dp(8)) y = barTop + dp(8);

        p.leftMargin = x;
        p.topMargin = y;
        card.setLayoutParams(p);
        card.setPivotX(w / 2f);
        card.setPivotY(h);
    }

    /* ============================ 派发 ============================ */

    private void fire(String id, String arg) {
        dismiss();
        host.onMenuAction(id, arg);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
