package local.dsh.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** 本地配置：dsh 地址、访问令牌、同步哪只宠物、悬浮球位置。 */
public class Prefs {

    private static final String FILE = "dsh_pet";
    private static final String KEY_URL = "url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_PET = "pet_id";
    private static final String KEY_X = "ball_x";
    private static final String KEY_Y = "ball_y";
    private static final String KEY_PEEK = "dock_peek";
    private static final String KEY_DOCK = "dock_side";
    private static final String KEY_RUNNING = "overlay_running";
    private static final String KEY_LOW_POWER = "low_power";
    /** 用户是否希望悬浮球一直开着。开机自启和看门狗只认这个，不看服务当前状态。 */
    private static final String KEY_DESIRED = "overlay_desired";
    /** 真实动画（和电脑同一段素材）。有些人更喜欢程序化演出，给个开关。 */
    private static final String KEY_REAL_ANIM = "real_anim";

    /**
     * 默认留空：仓库里不放任何人的机器地址和令牌。
     *
     * 首次打开 App 请展开首页的「电脑地址」，填
     *     http://<电脑在 Tailscale 里的地址>:8790
     * 令牌可以单独填，也可以直接写成 http://…:8790/?token=xxxx，
     * 解析时会把 token 自动拆出来（见 {@link #getToken()}）。
     */
    public static final String DEFAULT_URL = "";
    public static final String DEFAULT_TOKEN = "";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getUrl() {
        String v = sp.getString(KEY_URL, "");
        return (v == null || v.trim().isEmpty()) ? DEFAULT_URL : v.trim();
    }

    public void setUrl(String url) {
        sp.edit().putString(KEY_URL, url == null ? "" : url.trim()).apply();
    }

    /** 输入框里带了 ?token=xxx 就顺手拆出来，省得用户填两遍。 */
    public String getToken() {
        String fromUrl = tokenFromUrl(getUrl());
        if (fromUrl != null && !fromUrl.isEmpty()) return fromUrl;
        String v = sp.getString(KEY_TOKEN, "");
        return (v == null || v.trim().isEmpty()) ? DEFAULT_TOKEN : v.trim();
    }

    public void setToken(String token) {
        sp.edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /** 去掉 query/hash 与结尾斜杠，只留 scheme://host:port。 */
    public String baseUrl() {
        String u = getUrl();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** 追加 &token=...，用于已经有 ? 的 URL。 */
    public String authQuery() {
        String t = getToken();
        return t.isEmpty() ? "" : "&token=" + t;
    }

    /** 独立查询串，用于自己拼 ? 的 URL。 */
    public String tokenQuery() {
        String t = getToken();
        return t.isEmpty() ? "" : "?token=" + t;
    }

    /** 给 WebView 用的完整地址：没令牌就补上。 */
    public String webUrl() {
        String u = baseUrl();
        String t = getToken();
        if (t.isEmpty()) return u + "/";
        return u + "/?token=" + t;
    }

    public String getPetId() {
        String v = sp.getString(KEY_PET, "");
        return (v == null || v.trim().isEmpty()) ? "main" : v.trim();
    }

    public void setPetId(String id) {
        sp.edit().putString(KEY_PET, id == null ? "" : id.trim()).apply();
    }

    public int getX(int fallback) {
        return sp.getInt(KEY_X, fallback);
    }

    public int getY(int fallback) {
        return sp.getInt(KEY_Y, fallback);
    }

    public void setPos(int x, int y) {
        sp.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply();
    }

    /** 默认不藏：贴边后鱼还是整条的。想省地方才去 App 里打开「收进一半」。 */
    public boolean getPeek() {
        return sp.getBoolean(KEY_PEEK, false);
    }

    public void setPeek(boolean v) {
        sp.edit().putBoolean(KEY_PEEK, v).apply();
    }

    public boolean isOverlayDesired() {
        return sp.getBoolean(KEY_DESIRED, false);
    }

    public void setOverlayDesired(boolean v) {
        sp.edit().putBoolean(KEY_DESIRED, v).apply();
    }

    /** 默认开：真实动画是这一版的主要修复。 */
    public boolean getRealAnim() {
        return sp.getBoolean(KEY_REAL_ANIM, true);
    }

    public void setRealAnim(boolean v) {
        sp.edit().putBoolean(KEY_REAL_ANIM, v).apply();
    }

    /** 悬浮球是否在跑：网页里那只靠它决定显示还是隐藏。 */
    public boolean isOverlayRunning() {
        return sp.getBoolean(KEY_RUNNING, false);
    }

    public void setOverlayRunning(boolean v) {
        sp.edit().putBoolean(KEY_RUNNING, v).apply();
    }

    /** 低功耗：长轮询挂更久，换电。默认关——实时感比省几毫安重要。 */
    public boolean getLowPower() {
        return sp.getBoolean(KEY_LOW_POWER, false);
    }

    public void setLowPower(boolean v) {
        sp.edit().putBoolean(KEY_LOW_POWER, v).apply();
    }

    /** "" / "left" / "right" —— 上次贴在哪一边，重启后照着贴回去。 */
    public String getDock() {
        String v = sp.getString(KEY_DOCK, "");
        return v == null ? "" : v;
    }

    public void setDock(String side) {
        sp.edit().putString(KEY_DOCK, side == null ? "" : side).apply();
    }

    private static String tokenFromUrl(String url) {
        if (url == null) return null;
        try {
            return Uri.parse(url).getQueryParameter("token");
        } catch (Exception e) {
            return null;
        }
    }
}
