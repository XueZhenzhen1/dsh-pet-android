package local.dsh.pet;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * App 首页。
 *
 * 唯一的设计目标：打开就知道「它现在怎么样」，一指就能让它做事。
 * 所以顺序是 状态 → 能做什么 → 怎么连 → 偏好 → 说明，
 * 而不是把一堆开关平铺在首屏。
 *
 * 视觉上是一张深蓝渐变的海面 + 一张压在海面上的白色大卡片：
 * 渐变负责身份（和图标同色系），白卡负责信息层级，宫格负责动作。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTI = 0x21;

    private Prefs prefs;

    private TextView heroTitle;
    private TextView heroSub;
    private LinearLayout heroChipBox;
    private TextView primaryBtn;

    private LinearLayout connectCard;
    private LinearLayout connectFields;
    private TextView connectSummary;
    private TextView connectArrow;
    private EditText urlInput;
    private EditText tokenInput;
    private EditText petInput;
    private TextView testInfo;

    private LinearLayout quickCard;
    private LinearLayout stepsCard;
    private LinearLayout stepsBox;

    private boolean connectOpen;
    private Boolean online; // null = 还不知道
    /** 上次画保活卡时的权限状态；从系统设置回来发现变了就重画一遍。 */
    private String keepSig = "";
    /** 上次画保活卡时的权限状态；从系统设置回来发现变了就重画一遍。 */

    private final BroadcastReceiver overlayRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };
    private boolean rxOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setupImmersive();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.PAGE);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = Ui.column(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHero(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 内容整体压在 Hero 下沿 30dp 上：卡片「浮」在海面上，页面的层次一次就出来了
        LinearLayout content = Ui.column(this);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, 0, pad, Ui.dp(this, 40));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = -Ui.dp(this, 30);
        root.addView(content, clp);

        content.addView(buildQuickCard(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(buildConnectCard(), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, this));
        content.addView(buildKeepAliveCard(), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 14, this));
        content.addView(buildPrefsCard(), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, this));
        content.addView(buildStepsCard(), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, this));
        content.addView(buildGestureCard(), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, this));
        content.addView(buildAbout(), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 22, this));

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
        }
    }

    /* ============================ Hero ============================ */

    private View buildHero() {
        FrameLayout hero = new FrameLayout(this);
        hero.addView(new HeroBg(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.bar(this, "status_bar_height") + Ui.dp(this, 18), pad, Ui.dp(this, 46));

        // 顶行：头像 + 名字 / 状态胶囊
        LinearLayout top = Ui.row(this);
        android.widget.ImageView avatar = new android.widget.ImageView(this);
        avatar.setImageResource(R.drawable.ic_avatar);
        avatar.setBackground(Ui.round(0x33FFFFFF, 999, this));
        avatar.setClipToOutline(true);
        top.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout nameBox = Ui.column(this);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(this, 11);
        top.addView(nameBox, nlp);
        nameBox.addView(Ui.bold(this, "鱼球", 19f, 0xFFFFFFFF));
        TextView brand = Ui.text(this, "DeepSeek Harness 的手机伴生", 10.5f, 0xB3FFFFFF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 3);
        nameBox.addView(brand, blp);

        heroChipBox = Ui.row(this);
        top.addView(heroChipBox);
        col.addView(top);

        heroTitle = Ui.bold(this, "鱼球还没出来", 25f, 0xFFFFFFFF);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 22);
        col.addView(heroTitle, hlp);

        heroSub = Ui.text(this, "", 12.5f, 0xD9FFFFFF);
        heroSub.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 7);
        col.addView(heroSub, slp);

        LinearLayout btns = Ui.row(this);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.topMargin = Ui.dp(this, 18);
        col.addView(btns, blp2);

        primaryBtn = Ui.primary(this, "让鱼球出来", v -> toggleOverlay());
        btns.addView(primaryBtn, new LinearLayout.LayoutParams(
                0, Ui.dp(this, 48), 1.25f));

        TextView open = Ui.ghostOnDark(this, "打开 dsh", v -> {
            if (!PetService.RUNNING) {
                toast("先把鱼球叫出来");
                return;
            }
            startActivity(new Intent(this, WebActivity.class));
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 48), 1f);
        olp.leftMargin = Ui.dp(this, 10);
        btns.addView(open, olp);

        hero.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hero;
    }

    /** 海面：竖向渐变 + 两团高光 + 底部一道波浪。纯 Canvas，没有一张图。 */
    private static class HeroBg extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path wave = new Path();

        HeroBg(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            p.setShader(new LinearGradient(0, 0, w * 0.55f, h,
                    new int[]{0xFF052E7E, 0xFF0B5FD6, 0xFF1D86E6},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            p.setShader(new RadialGradient(w * 0.86f, h * 0.10f, h * 0.85f,
                    new int[]{0x33FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.86f, h * 0.10f, h * 0.85f, p);
            p.setShader(new RadialGradient(w * 0.04f, h * 0.82f, h * 0.55f,
                    new int[]{0x2657D3F0, 0x0057D3F0}, null, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.04f, h * 0.82f, h * 0.55f, p);
            p.setShader(null);

            wave.reset();
            wave.moveTo(0, h * 0.74f);
            wave.cubicTo(w * 0.28f, h * 0.66f, w * 0.52f, h * 0.90f, w, h * 0.74f);
            wave.lineTo(w, h);
            wave.lineTo(0, h);
            wave.close();
            p.setColor(0x14FFFFFF);
            c.drawPath(wave, p);
        }
    }

    /* ============================ 快捷动作 ============================ */

    private View buildQuickCard() {
        quickCard = Ui.card(this);
        quickCard.setElevation(Ui.dp(this, 8));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.bold(this, "现在就能让它做", 14f, Ui.INK),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hint = Ui.text(this, "点完自动退到后台", 10.5f, Ui.INK3);
        head.addView(hint);
        Ui.fill(quickCard, head);

        LinearLayout g1 = Ui.row(this);
        LinearLayout.LayoutParams g1p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g1p.topMargin = Ui.dp(this, 10);
        quickCard.addView(g1, g1p);
        g1.addView(Ui.tile(this, Glyph.CHAT, "说句话", () -> doAction("whisper", null)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        g1.addView(Ui.tile(this, Glyph.PLAY, "演动作", () -> doAction("anim", "悠闲哼歌")),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        g1.addView(Ui.tile(this, Glyph.WALLET, "看余额", () -> doAction("balance", null)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        g1.addView(Ui.tile(this, Glyph.SUN, "查天气", () -> doAction("environment", null)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return quickCard;
    }

    /* ============================ 连接 ============================ */

    private View buildConnectCard() {
        connectCard = Ui.card(this);

        LinearLayout row = Ui.row(this);
        row.setClickable(true);
        row.setBackground(Ui.pressable(this, Color.TRANSPARENT, 0x0F0B62D8, 14));
        row.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));

        Glyph g = new Glyph(this, Glyph.POWER, Ui.BRAND);
        row.addView(g, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));

        LinearLayout col = Ui.column(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 12);
        row.addView(col, clp);
        col.addView(Ui.text(this, "电脑地址", 14f, Ui.INK));
        connectSummary = Ui.text(this, prefs.baseUrl(), 11.5f, Ui.INK3);
        connectSummary.setSingleLine(true);
        connectSummary.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 3);
        col.addView(connectSummary, slp);

        connectArrow = Ui.text(this, "展开", 11.5f, Ui.BRAND);
        row.addView(connectArrow);

        row.setOnClickListener(v -> {
            connectOpen = !connectOpen;
            applyConnectState();
        });
        Ui.fill(connectCard, row);

        connectFields = Ui.column(this);
        Ui.fill(connectCard, connectFields);

        urlInput = Ui.labeledField(this, connectFields,
                "地址", prefs.getUrl(), "http://100.x.x.x:8790");
        tokenInput = Ui.labeledField(this, connectFields,
                "访问令牌", prefs.getToken(), "pocket 的 token");
        petInput = Ui.labeledField(this, connectFields,
                "宠物 ID", prefs.getPetId(), "main");

        LinearLayout btns = Ui.row(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 14);
        connectFields.addView(btns, blp);

        TextView save = Ui.primary(this, "保存并重连", v -> saveAndReconnect());
        btns.addView(save, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));

        TextView test = Ui.secondary(this, "测试", v -> testSync());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 46), 0.62f);
        tlp.leftMargin = Ui.dp(this, 10);
        btns.addView(test, tlp);

        testInfo = Ui.text(this, "", 11.5f, Ui.INK2);
        testInfo.setLineSpacing(Ui.dp(this, 4), 1f);
        LinearLayout.LayoutParams tilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tilp.topMargin = Ui.dp(this, 12);
        connectFields.addView(testInfo, tilp);

        connectOpen = !PetService.RUNNING || online == null;
        applyConnectState();
        return connectCard;
    }

    private void applyConnectState() {
        connectFields.setVisibility(connectOpen ? View.VISIBLE : View.GONE);
        connectArrow.setText(connectOpen ? "收起" : "展开");
    }

    /* ============================ 偏好 ============================ */

    private View buildPrefsCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.bold(this, "偏好", 14f, Ui.INK));

        Switch peek = Ui.toggle(this, prefs.getPeek());
        peek.setOnCheckedChangeListener((b, on) -> {
            prefs.setPeek(on);
            if (PetService.RUNNING) {
                startService(new Intent(this, PetService.class));
            }
        });
        card.addView(Ui.settingRow(this, Glyph.HOME, "贴边后收进一半",
                "关着是整条鱼贴边，开着更省地方", peek), Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));

        card.addView(Ui.hairline(this, 34));

        Switch low = Ui.toggle(this, prefs.getLowPower());
        low.setOnCheckedChangeListener((b, on) -> {
            prefs.setLowPower(on);
            // 同步线程在服务里，重启服务最省事，也保证新参数立刻生效
            if (PetService.RUNNING) {
                startService(new Intent(this, PetService.class));
            }
        });
        card.addView(Ui.settingRow(this, Glyph.POWER, "低功耗同步",
                "长轮询挂更久，状态最多慢几秒，换一格电", low));

        card.addView(Ui.hairline(this, 34));

        Switch realAnim = Ui.toggle(this, prefs.getRealAnim());
        realAnim.setOnCheckedChangeListener((b, on) -> prefs.setRealAnim(on));
        card.addView(Ui.settingRow(this, Glyph.PLAY, "真实动作动画",
                "播电脑上同一段动画；关掉只用本机变形", realAnim));

        if (AnimLib.serverMissing()) {
            TextView warn = Ui.text(this,
                    "电脑上还没有转换好的动作素材：点动作会用本机变形代替。"
                            + "在电脑的项目目录跑一次 tools/convert-anims.py 就能补上。",
                    11f, Ui.WARN);
            warn.setLineSpacing(Ui.dp(this, 2), 1f);
            Ui.fill(card, warn);
            Ui.setMargins(warn, this, 10, 4, 10, 0);
        }

        if (AnimLib.serverMissing()) {
            TextView warn = Ui.text(this,
                    "电脑上还没有转换好的动作素材：点动作会用本机变形代替。"
                            + "在电脑的项目目录跑一次 tools/convert-anims.py 就能补上。",
                    11f, Ui.WARN);
            warn.setLineSpacing(Ui.dp(this, 2), 1f);
            Ui.fill(card, warn);
            Ui.setMargins(warn, this, 10, 4, 10, 0);
        }

        card.addView(Ui.hairline(this, 34));
        card.addView(Ui.listRow(this, Glyph.GRID, "动作缓存",
                AnimLib.usage(this) + "，点一下清空", () -> {
                    AnimLib.clear(this);
                    Toast.makeText(this, "动作缓存已清空，下次播放会重新下载", Toast.LENGTH_SHORT).show();
                    recreate();
                }));

        return card;
    }

    /* ============================ 保活 ============================ */

    /**
     * 保活不是「加一个权限」就完事的：安卓上要同时堵住四个口子
     * （前台服务、电省电白名单、系统作业看门狗、ROM 自启动）。
     * 这里把每一件事的状态都摊开给用户看，能点的直接跳设置。
     */
    private View buildKeepAliveCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.bold(this, "保活", 14f, Ui.INK));
        card.addView(Ui.text(this, "悬浮层被系统收掉时，怎么把它叫回来", 11.5f, Ui.INK3),
                Ui.lpT(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 4, this));

        boolean batt = Keeper.isIgnoringBattery(this);
        boolean guardNow = Keeper.isGuardEnabled(this);
        keepSig = batt + "/" + guardNow;
        Ui.fill(card, Ui.listRow(this, Glyph.POWER, "电池优化白名单",
                batt ? "已加入，省电策略不会顺手清掉它" : "还没加入，夜里可能被系统收掉",
                this::askBattery));

        boolean guard = guardNow;
        Switch g = Ui.toggle(this, guard);
        g.setOnCheckedChangeListener((b, on) -> {
            b.setChecked(guard);            // 这里不改状态，真正的开关在系统设置里
            openAccessibility();
        });
        Ui.fill(card, Ui.settingRow(this, Glyph.SHIELD, "无障碍守护",
                guard ? "已开启：每 30 秒看一眼，被清掉就拉回来"
                        : "可选。不读屏幕内容，只用来保活，最扛清理",
                g));

        Switch boot = Ui.toggle(this, prefs.isOverlayDesired());
        boot.setOnCheckedChangeListener((b, on) -> {
            prefs.setOverlayDesired(on);
            if (on) {
                Keeper.scheduleWatchdog(this);
                if (Settings.canDrawOverlays(this) && !PetService.RUNNING) {
                    Keeper.startOverlay(this, "prefs");
                }
            } else {
                Keeper.cancelWatchdog(this);
            }
        });
        Ui.fill(card, Ui.settingRow(this, Glyph.HOME, "重启后自动恢复",
                "开机、更新 App 后自动把鱼球挂回去", boot));

        TextView note = Ui.text(this,
                "小米 / 华为 / OPPO / vivo 还要在系统的「自启动管理」里允许鱼球，"
                        + "否则开机后系统会直接拦掉，这一步代码绕不过去。",
                11f, Ui.INK3);
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.fill(card, note);
        Ui.setMargins(note, this, 10, 6, 10, 0);

        return card;
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "这台机器没有这个设置页，去「电池 → 应用」里找鱼球",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "在列表里找到「鱼球」，打开开关", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "打不开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    /* ============================ 三步走 ============================ */

    private View buildStepsCard() {
        stepsCard = Ui.card(this);
        stepsCard.addView(Ui.bold(this, "第一次用，三步", 14f, Ui.INK));

        stepsBox = Ui.column(this);
        stepsCard.addView(stepsBox, Ui.lpT(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
        return stepsCard;
    }

    private void renderSteps() {
        stepsBox.removeAllViews();
        boolean perm = Settings.canDrawOverlays(this);

        Ui.fill(stepsBox, step(perm, "1",
                "允许它盖在其他应用上",
                perm ? "已授予" : "系统设置里的「显示在其他应用上层」",
                perm ? null : () -> askOverlay()));

        Ui.fill(stepsBox, step(PetService.RUNNING, "2",
                "让鱼球出来",
                PetService.RUNNING ? "正在屏幕上陪着你" : "点一下就会贴在屏幕边上",
                PetService.RUNNING ? null : () -> toggleOverlay()));

        Ui.fill(stepsBox, step(online != null && online, "3",
                "连上你电脑上的 dsh",
                online == null ? "还没测过" : (online ? "通着呢" : "连不上，展开上面那张卡看原因"),
                online != null && online ? null : () -> {
                    connectOpen = true;
                    applyConnectState();
                    testSync();
                }));

        boolean allDone = perm && PetService.RUNNING && Boolean.TRUE.equals(online);
        stepsCard.setVisibility(allDone ? View.GONE : View.VISIBLE);
    }

    private View step(boolean done, String num, String title, String sub, Runnable action) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 2), Ui.dp(this, 9), Ui.dp(this, 2), Ui.dp(this, 9));

        TextView badge = Ui.stepBadge(this, done ? "✓" : num, done);
        r.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));

        LinearLayout col = Ui.column(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 12);
        r.addView(col, clp);
        col.addView(Ui.text(this, title, 13.5f, done ? Ui.INK3 : Ui.INK));
        TextView s = Ui.text(this, sub, 11f, Ui.INK3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 3);
        col.addView(s, slp);

        if (action != null) {
            TextView btn = Ui.text(this, "去设置", 11.5f, Ui.BRAND,
                    android.graphics.Typeface.BOLD);
            btn.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
            btn.setOnClickListener(v -> action.run());
            r.addView(btn);
        }
        return r;
    }

    /* ============================ 手势说明 ============================ */

    private View buildGestureCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.bold(this, "手势", 14f, Ui.INK));

        String[][] rows = {
                {"点一下", "打开功能菜单"},
                {"点两下", "直接进 dsh"},
                {"长按", "摸摸它（不会关掉）"},
                {"上滑 / 下滑", "开菜单 / 贴边收好"},
                {"左右甩", "停到那一侧"},
                {"拖着走", "松手自动吸边"},
        };
        for (String[] row : rows) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            TextView k = Ui.text(this, row[0], 12.5f, Ui.INK, android.graphics.Typeface.BOLD);
            k.setSingleLine(true);
            r.addView(k, new LinearLayout.LayoutParams(
                    Ui.dp(this, 106), ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(Ui.text(this, row[1], 12.5f, Ui.INK2),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Ui.fill(card, r);
        }

        TextView note = Ui.text(this,
                "网页里那只已经常驻关闭，手机上只有这一只；电脑浏览器里那只照旧。",
                11f, Ui.INK3);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        card.addView(note, Ui.lpT(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, this));
        return card;
    }

    /* ============================ 关于 ============================ */

    private View buildAbout() {
        LinearLayout col = Ui.column(this);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        TextView v = Ui.text(this, "鱼球 4.0 · dsh-pet 0.4.9", 11f, Ui.INK3);
        col.addView(v);
        TextView v2 = Ui.text(this, "本机悬浮 + 你电脑上的 DeepSeek Harness", 10.5f, Ui.INK4);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(this, 4);
        col.addView(v2, lp2);
        return col;
    }

    /* ============================ 行为 ============================ */

    private void toggleOverlay() {
        if (PetService.RUNNING) {
            stopService(new Intent(this, PetService.class));
            PetService.RUNNING = false;
            refresh();
            toast("收好了。电脑上那只不受影响。");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("先授予悬浮窗权限");
            askOverlay();
            return;
        }
        startPetService(null, null);
        toast("鱼球出来了");
        refresh();
    }

    private void startPetService(String action, String arg) {
        Intent i = new Intent(this, PetService.class);
        if (action != null) {
            i.putExtra(PetService.EXTRA_DO, action);
            if (arg != null) i.putExtra("arg", arg);
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    /**
     * 宫格上的动作：起服务 → 把任务塞进去 → 自己退到后台。
     *
     * 退后台这一步是关键：用户要的是「在微信上也能让它说句话」，
     * 如果点完还停在设置页，鱼球的气泡被自己的 Activity 挡着，等于没做。
     */
    private void doAction(String action, String arg) {
        if (!Settings.canDrawOverlays(this)) {
            toast("先授予悬浮窗权限");
            askOverlay();
            return;
        }
        startPetService(action, arg);
        toast("已交给鱼球，正在回到桌面…");
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> moveTaskToBack(true), 420);
    }

    private void saveAndReconnect() {
        String u = urlInput.getText().toString().trim();
        if (u.isEmpty()) {
            toast("地址不能为空");
            return;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        urlInput.setText(u);
        prefs.setUrl(u);
        prefs.setToken(tokenInput.getText().toString().trim());
        String pid = petInput.getText().toString().trim();
        prefs.setPetId(pid.isEmpty() ? "main" : pid);

        connectSummary.setText(prefs.baseUrl());
        toast("已保存，正在按新配置重连");
        if (PetService.RUNNING) {
            stopService(new Intent(this, PetService.class));
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> startPetService(null, null), 300);
        }
        online = null;
        refresh();
        testSync();
    }

    /* ============================ 生命周期 ============================ */

    @Override
    protected void onResume() {
        super.onResume();
        if (!rxOn) {
            IntentFilter f = new IntentFilter(PetService.ACTION_OVERLAY_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(overlayRx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(overlayRx, f);
            }
            rxOn = true;
        }
        refresh();
        probeQuiet();
        // 从电池/无障碍设置页回来，卡片上的状态可能已经变了：重画一遍，省得用户以为没生效
        String sig = Keeper.isIgnoringBattery(this) + "/" + Keeper.isGuardEnabled(this);
        if (!keepSig.isEmpty() && !keepSig.equals(sig)) {
            keepSig = sig;
            recreate();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rxOn) {
            try {
                unregisterReceiver(overlayRx);
            } catch (Exception ignored) {
            }
            rxOn = false;
        }
    }

    private void refresh() {
        boolean running = PetService.RUNNING;

        if (heroTitle != null) {
            heroTitle.setText(running ? "鱼球在陪着你" : "鱼球还没出来");
        }
        if (primaryBtn != null) {
            primaryBtn.setText(running ? "让它先回去" : "让鱼球出来");
        }
        if (heroSub != null) {
            String s;
            if (!running) {
                s = "点上面那个按钮，它会贴在屏幕边上，双击就能进 dsh。";
            } else if (online == null) {
                s = "悬浮球已就位 · 正在确认电脑那头…";
            } else if (online) {
                s = "已连上电脑 · 说话、表情、动作都是实时同步的";
            } else {
                s = "悬浮球在，但连不上电脑 · 展开「电脑地址」看是哪一步断了";
            }
            heroSub.setText(s);
        }
        if (heroChipBox != null) {
            heroChipBox.removeAllViews();
            String text;
            int color;
            if (!running) {
                text = "未启动";
                color = 0xCCFFFFFF;
            } else if (online == null) {
                text = "连接中";
                color = 0xFFB9E0FF;
            } else if (online) {
                text = "在线";
                color = 0xFF7CF0C0;
            } else {
                text = "离线";
                color = 0xFFFFB4B4;
            }
            heroChipBox.addView(Ui.chip(this, text, color, 0xFFFFFFFF, 0x26FFFFFF));
        }
        if (connectSummary != null) connectSummary.setText(prefs.baseUrl());
        if (stepsCard != null) renderSteps();
        applyConnectState();
    }

    private void setupImmersive() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        // Hero 是深色海面，状态栏要白字，所以这里反过来：不加 LIGHT_STATUS_BAR
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /* ============================ 探测 ============================ */

    private void testSync() {
        testInfo.setText("正在连…");
        new Thread(() -> {
            final String msg = probeSync();
            runOnUiThread(() -> {
                testInfo.setText(msg);
                online = msg.contains("已连上电脑");
                refresh();
            });
        }, "dsh-pet-test").start();
    }

    /** 回到页面时静默探一次，只更新那颗状态胶囊，不改输入框里的东西。 */
    private void probeQuiet() {
        if (!PetService.RUNNING) {
            online = null;
            return;
        }
        new Thread(() -> {
            final boolean ok = quickProbe();
            runOnUiThread(() -> {
                online = ok;
                refresh();
            });
        }, "dsh-pet-probe").start();
    }

    private boolean quickProbe() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(prefs.baseUrl()
                    + "/dsh-pet-7340/state?since=0&timeout=1000" + prefs.authQuery()).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 探一次同步接口，并把失败原因翻译成人话。
     *
     * 手机上看不到电脑，能拿到的只有异常类型和 HTTP 码，所以这里把四种常见情况分开说：
     * 端口没开（dsh 没启动）、连不上（Tailscale / 休眠）、令牌不对、插件没装。
     */
    private String probeSync() {
        final String base = prefs.baseUrl();
        if (base.isEmpty()) {
            return "还没填电脑地址\n"
                    + "展开上面的「电脑地址」，填 http://<电脑>:8790\n"
                    + "电脑和手机都要连在同一个 Tailscale 网络里。";
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(
                    base + "/dsh-pet-7340/state?since=0&timeout=1000" + prefs.authQuery()).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            int code = c.getResponseCode();

            if (code == 401 || code == 403) {
                return "连上了，但令牌不对（HTTP " + code + "）\n"
                        + "在电脑浏览器打开 " + base + "/ ，\n"
                        + "用地址栏里的 token 重新复制一次。";
            }
            if (code == 404) {
                return "连上了，但里面没有 dsh-pet 插件（HTTP 404）\n"
                        + "电脑上 dsh 的 web profile 要装好并启用 dsh-pet。";
            }
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                return "连上了，但返回 HTTP " + code + "\n" + shorten(body, 120);
            }

            JSONObject o = new JSONObject(body);
            long counter = o.optLong("counter", 0);
            JSONObject pets = o.optJSONObject("pets");
            JSONObject pet = pets == null ? null : pets.optJSONObject(prefs.getPetId());
            if (pet == null) {
                return "通道已通（counter=" + counter + "）\n"
                        + "但电脑上的宠物还没上线。\n"
                        + "在电脑浏览器里打开 dsh 页面，鱼球出现以后，这里就有台词和状态了。";
            }

            String say = "（没说话）";
            String extra = "";
            JSONObject s = pet.optJSONObject("say");
            JSONObject sd = s == null ? null : s.optJSONObject("data");
            if (sd != null && !sd.optBoolean("clear", false)) {
                String t = sd.optString("text", "");
                if (!t.isEmpty()) say = "「" + t + "」";
                String img = sd.optString("image", "");
                if (!img.isEmpty()) extra = "\n配图：" + img;
            }
            JSONObject sl = pet.optJSONObject("sleep");
            JSONObject sld = sl == null ? null : sl.optJSONObject("data");
            if (sld != null && sld.optBoolean("sleeping", false)) extra += "\n状态：睡觉中";
            return "已连上电脑 ✓（counter=" + counter + "）\n它现在说：" + say + extra;

        } catch (java.net.UnknownHostException e) {
            return "地址解析不了\n检查「地址」写没写错：" + base;
        } catch (java.net.ConnectException e) {
            String m = String.valueOf(e.getMessage());
            if (m.toLowerCase().contains("refused")) {
                return "电脑上的 8790 端口没开 —— dsh 没启动。\n"
                        + "· 在电脑上跑 `dsh web`（或你包装的常驻脚本）\n"
                        + "· 起来后插件要十几秒才监听，稍等再点「重新连接」";
            }
            return "连不上 " + base + "\n" + m;
        } catch (java.net.SocketTimeoutException e) {
            return "6 秒没连上，按这个顺序查：\n"
                    + "① 电脑上 dsh 是不是关了（最常见，8790 没监听）\n"
                    + "② 电脑托盘里 Tailscale 是不是 Connected\n"
                    + "③ 电脑有没有睡眠 / 合盖\n"
                    + "电脑上 dsh 的窗口一关，手机这边就必然连不上。";
        } catch (org.json.JSONException e) {
            return "连上了，但返回的不是状态数据\n地址可能指到了别的服务，确认端口是 8790。";
        } catch (Exception e) {
            return "连不上：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " · " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.trim().replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    /* ============================ 权限 ============================ */

    private void askOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("打不开权限页，去系统设置里手动开「显示在其他应用上层」");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
