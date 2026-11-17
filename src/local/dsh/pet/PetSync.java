package local.dsh.pet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 跟电脑上的 dsh-pet 插件同步状态。
 *
 * 插件宿主侧把每只宠物的 say / anim / sleep 都写在 /dsh-pet-7340/state 里，
 * 并且支持长轮询（since + timeout）：状态一变就立刻返回。这里就靠它做「完全同步」——
 * 电脑上的鱼说了什么、睡了没、在忙什么，手机上同一个瞬间就有。
 */
public class PetSync {

    public interface Listener {
        /** 收到一份新状态。回调在主线程。 */
        void onPetState(State s);

        /** 连接状态变化。回调在主线程。 */
        void onStatus(boolean online, String note);
    }

    /** 一次状态快照。字段都做了兜底，缺数据时是空串/false。 */
    public static class State {
        public long counter;
        public String text = "";
        public String image = "";
        public long duration;
        public boolean hasSay;
        public long sayCounter = -1;
        public String anim = "";
        public String work = "";
        public boolean sleeping;
        /** 插件注册的自定义菜单项（sections.menu.items），可能为 null。 */
        public org.json.JSONArray menu;
        /** 从 sections.balance 折出来的一句人话，如「钱包里还有 ¥9.57」。空串 = 没数据。 */
        public String balanceText = "";
        /** 从 sections.env 折出来的一句人话，如「武汉市 阴 12°C（体感 9°C）」。 */
        public String envText = "";
    }

    /**
     * 本地缓存目录（filesDir）。配置和表情包落到这里，
     * 面板第二次打开就不用等网络——这是「反应快」最省事的一半。
     */
    private static File CACHE;

    public static void setCacheDir(File dir) {
        CACHE = dir;
    }

    private static File cacheDir() {
        if (CACHE == null) return null;
        File d = new File(CACHE, "cache");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 16000;

    private final Prefs prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean lowPower;
    private volatile HttpURLConnection active;
    private Thread worker;

    public PetSync(Prefs prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
        this.lowPower = prefs.getLowPower();
    }

    /** 低功耗：长轮询挂更久、两轮之间多歇一会。电池吃紧时开。 */
    public void setLowPower(boolean on) {
        this.lowPower = on;
    }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "dsh-pet-sync");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        HttpURLConnection c = active;
        active = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void loop() {
        long since = 0;
        int backoff = 1;
        while (running) {
            HttpURLConnection c = null;
            // 新装/没配置时地址是空的：别拿 "" 去 new URL() 疯狂抛异常，安静等用户填。
            String base = prefs.baseUrl();
            if (base.isEmpty()) {
                main.post(() -> listener.onStatus(false, "还没填电脑地址"));
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException ie) {
                    break;
                }
                continue;
            }
            try {
                // since 与 timeout 必须成对出现（缺一个会被判 bad-request）；
                // since=0 保证第一次立刻拿到当前状态。
                // 低功耗模式把长轮询拉到 25 秒：唤醒次数少一半，状态延迟最多多几秒。
                int hold = lowPower ? 25000 : 10000;
                String url = base
                        + "/dsh-pet-7340/state?since=" + since + "&timeout=" + hold
                        + prefs.authQuery();
                c = (HttpURLConnection) new URL(url).openConnection();
                active = c;
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(hold + 8000);
                c.setRequestProperty("accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) throw new IllegalStateException("HTTP " + code);
                String body = readAll(c.getInputStream());
                JSONObject o = new JSONObject(body);
                since = o.optLong("counter", since);
                State s = parse(o, prefs.getPetId());
                backoff = 1;
                main.post(() -> {
                    listener.onStatus(true, "");
                    listener.onPetState(s);
                });
                Thread.sleep(lowPower ? 600 : 120);
            } catch (Exception e) {
                if (!running) break;
                String note = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                main.post(() -> listener.onStatus(false, note));
                int delayMs = Math.min(20, backoff) * 1000;
                backoff = Math.min(20, backoff + 2);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    break;
                }
            } finally {
                active = null;
                if (c != null) c.disconnect();
            }
        }
    }

    private static State parse(JSONObject o, String petId) {
        State s = new State();
        s.counter = o.optLong("counter", 0);

        JSONObject pets = o.optJSONObject("pets");
        JSONObject pet = pets == null ? null : pets.optJSONObject(petId);
        if (pet != null) {
            JSONObject say = pet.optJSONObject("say");
            if (say != null) {
                s.sayCounter = say.optLong("counter", -1);
                JSONObject d = say.optJSONObject("data");
                if (d != null && !d.optBoolean("clear", false)) {
                    s.text = d.optString("text", "");
                    s.image = d.optString("image", "");
                    s.duration = d.optLong("duration", 0);
                    s.hasSay = !s.text.isEmpty();
                }
            }
            JSONObject anim = pet.optJSONObject("anim");
            if (anim != null) {
                JSONObject d = anim.optJSONObject("data");
                if (d != null) {
                    s.anim = d.optString("name", "");
                    if (s.anim.isEmpty()) s.anim = d.optString("url", "");
                }
            }
            JSONObject sleep = pet.optJSONObject("sleep");
            if (sleep != null) {
                JSONObject d = sleep.optJSONObject("data");
                if (d != null) s.sleeping = d.optBoolean("sleeping", false);
            }
        }

        JSONObject sections = o.optJSONObject("sections");
        if (sections != null) {
            JSONObject ws = sections.optJSONObject("workStatus");
            if (ws != null) {
                JSONObject d = ws.optJSONObject("data");
                if (d != null) s.work = d.optString("state", "");
            }
            JSONObject m = sections.optJSONObject("menu");
            JSONObject md = m == null ? null : m.optJSONObject("data");
            if (md != null) s.menu = md.optJSONArray("items");

            // 余额：/balance 只把结果写进 sections，不会写 say —— 所以手机得自己读这一节，
            // 否则点了「查看余额」永远只能显示「翻一下钱包……」。
            JSONObject bal = sections.optJSONObject("balance");
            JSONObject bd = bal == null ? null : bal.optJSONObject("data");
            JSONObject inner = bd == null ? null : bd.optJSONObject("data");
            org.json.JSONArray wallets = inner == null ? null : inner.optJSONArray("wallets");
            if (wallets != null && wallets.length() > 0) {
                JSONObject w0 = wallets.optJSONObject(0);
                if (w0 != null) {
                    String total = w0.optString("total", "");
                    String granted = w0.optString("granted", "");
                    if (!total.isEmpty()) {
                        s.balanceText = "钱包里还有 ¥" + total;
                        if (!granted.isEmpty() && !"0.00".equals(granted) && !"0".equals(granted)) {
                            s.balanceText += "（含赠送 ¥" + granted + "）";
                        }
                    }
                }
            }

            // 天气：同一条道理，/environment 也是只写 sections.env
            JSONObject env = sections.optJSONObject("env");
            JSONObject ed = env == null ? null : env.optJSONObject("data");
            JSONObject w = ed == null ? null : ed.optJSONObject("weather");
            if (w != null) {
                String city = w.optString("city", "");
                String sum = w.optString("summary", "");
                int t = w.optInt("temperatureC", -999);
                int feel = w.optInt("apparentC", -999);
                if (t > -100) {
                    StringBuilder sb = new StringBuilder();
                    if (!city.isEmpty()) sb.append(city).append(' ');
                    if (!sum.isEmpty()) sb.append(sum).append(' ');
                    sb.append(t).append("°C");
                    if (feel > -100 && feel != t) sb.append("（体感 ").append(feel).append("°C）");
                    s.envText = sb.toString();
                }
            }
        }
        return s;
    }

    /**
     * 单独拉一次状态快照。
     *
     * 余额 / 天气这类「查一次给我看」的动作不适合等长轮询——那边可能刚好挂在一个
     * 已经建立、还没到期的轮询上。这里另开一条一次性请求，拿到就显示。
     */
    public static State fetchSnapshot(Prefs prefs) {
        HttpURLConnection c = null;
        try {
            String url = prefs.baseUrl()
                    + "/dsh-pet-7340/state?since=0&timeout=1000" + prefs.authQuery();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(9000);
            c.setRequestProperty("accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            return parse(new JSONObject(readAll(c.getInputStream())), prefs.getPetId());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------------- 反向：让电脑上的鱼跟着手机动 ---------------- */

    /** 让桌面的鱼播一段动作（点手机上的鱼 → 电脑也挥手）。 */
    public void pokeAnim(String name) {
        if (name == null || name.isEmpty()) return;
        post("/dsh-pet-7340/anim?pet=" + enc(prefs.getPetId()) + "&surface=any",
                "{\"name\":\"" + esc(name) + "\"}");
    }

    /** 菜单里点播的动作：不节流，点了就播。 */
    public void playAnim(String name) {
        if (name == null || name.isEmpty()) return;
        post("/dsh-pet-7340/anim?pet=" + enc(prefs.getPetId()) + "&surface=any",
                "{\"name\":\"" + esc(name) + "\"}");
    }

    /** 让它说一句碎碎念；带 image 就是「配这张表情说一句」。 */
    public void whisper(String image) {
        String path = "/dsh-pet-7340/whisper?pet=" + enc(prefs.getPetId()) + "&surface=any";
        if (image != null && !image.isEmpty()) path += "&image=" + enc(image);
        post(path, "{}");
    }

    /** 睡觉 / 叫醒。 */
    public void setSleeping(boolean asleep) {
        post("/dsh-pet-7340/" + (asleep ? "sleep" : "wake")
                + "?pet=" + enc(prefs.getPetId()), "{}");
    }

    /** 让它把余额演一遍（动画在电脑上，手机同步到台词/状态）。 */
    public void showBalance() {
        post("/dsh-pet-7340/balance?pet=" + enc(prefs.getPetId()) + "&surface=any", "{}");
    }

    /** 让它查一次天气并演出来。 */
    public void showEnvironment() {
        post("/dsh-pet-7340/environment?pet=" + enc(prefs.getPetId())
                + "&surface=any&fresh=1", "{}");
    }

    /** 点一下插件注册的自定义菜单项（比如「吃白饭」）。 */
    public void clickMenu(String owner, String label) {
        post("/dsh-pet-7340/menu/click",
                "{\"owner\":\"" + esc(owner) + "\",\"label\":\"" + esc(label)
                        + "\",\"pet\":\"" + esc(prefs.getPetId()) + "\"}");
    }

    /**
     * 只读本地缓存（内存没有就读磁盘），不发网络请求。
     *
     * 面板一打开就要出动作清单，等网络那 100~200ms 会先闪一下「正在读取」。
     * 有缓存就先画，刷新放后台。
     */
    public static JSONObject cachedConfig() {
        if (memConfig != null) return memConfig;
        File f = cacheDir() == null ? null : new File(cacheDir(), "config.json");
        if (f == null || !f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(readFile(f));
            if (o.has("main")) {
                memConfig = o;
                return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject memConfig;

    /** 读一次合并后的配置（表情清单、动作清单）。失败返回 null。 */
    public static JSONObject fetchConfig(Prefs prefs) {
        HttpURLConnection c = null;
        try {
            String url = prefs.baseUrl() + "/dsh-pet-7340/config" + prefs.tokenQuery();
            c = (HttpURLConnection) new URL(url).openConnection();
            // 面板里等不起 20 秒：配置读不到就先给提示，别让「正在读取」一直转。
            c.setConnectTimeout(4500);
            c.setReadTimeout(7000);
            c.setRequestProperty("accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) return null;
            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            if (!o.has("main")) return null;
            memConfig = o;
            File f = cacheDir() == null ? null : new File(cacheDir(), "config.json");
            if (f != null) writeFile(f, o.toString());
            return o;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 让桌面的鱼弹一句话（自测用）。 */
    public void pokeSay(String text, long durationMs) {
        if (text == null || text.isEmpty()) return;
        String body = "{\"text\":\"" + text.replace("\"", "\\\"") + "\",\"duration\":" + durationMs + "}";
        post("/dsh-pet-7340/broadcast?pet=" + enc(prefs.getPetId()) + "&surface=any", body);
    }

    private void post(String path, String body) {
        Thread t = new Thread(() -> {
            HttpURLConnection c = null;
            try {
                String url = prefs.baseUrl() + path + prefs.authQuery();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(10000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                c.getOutputStream().write(body.getBytes("UTF-8"));
                c.getResponseCode();
                readAll(c.getInputStream());
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }, "dsh-pet-post");
        t.setDaemon(true);
        t.start();
    }

    /** 拉一张表情包（气泡配图）。失败返回 null，静默降级成纯文字。 */
    public static Bitmap fetchMeme(Prefs prefs, String name) {
        return fetchMeme(prefs, name, 0);
    }

    /**
     * 拉一张表情包，并按最长边 maxPx 解码。
     *
     * 面板里要一次显示二十多张缩略图，按原图解码会吃掉几十兆堆——
     * 先用 inJustDecodeBounds 量一下尺寸，再挑采样率，缩略图只占几百 KB。
     * maxPx <= 0 表示按老规矩半采样（气泡里那张大图用它）。
     */
    public static Bitmap fetchMeme(Prefs prefs, String name, int maxPx) {
        if (name == null || name.isEmpty()) return null;

        // 先在本地找：表情就 27 张，全部预取到磁盘以后，点开面板就是本机读文件，
        // 不用再看网络脸色。
        byte[] data = null;
        File cached = memeFile(name);
        if (cached != null && cached.isFile() && cached.length() > 512) {
            try {
                data = readBytes(new FileInputStream(cached));
            } catch (Exception ignored) {
            }
        }

        HttpURLConnection c = null;
        try {
            if (data == null || data.length == 0) {
                String url = prefs.baseUrl() + "/dsh-pet-7340/pic/memes/" + enc(name) + ".png"
                        + prefs.tokenQuery();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(12000);
                data = readBytes(c.getInputStream());
                if (data.length == 0) return null;
                if (cached != null) writeBytes(cached, data);
            }

            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);

            BitmapFactory.Options o = new BitmapFactory.Options();
            if (maxPx > 0 && probe.outWidth > 0 && probe.outHeight > 0) {
                int sample = 1;
                int longest = Math.max(probe.outWidth, probe.outHeight);
                while (longest / sample > maxPx) sample *= 2;
                o.inSampleSize = sample;
            } else {
                o.inSampleSize = 2;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------------- 工具 ---------------- */

    /** 只把表情图搬到本地，不解码。预热用，省内存。 */
    public static void prefetchMeme(Prefs prefs, String name) {
        if (name == null || name.isEmpty()) return;
        File f = memeFile(name);
        if (f != null && f.isFile() && f.length() > 512) return;
        HttpURLConnection c = null;
        try {
            String url = prefs.baseUrl() + "/dsh-pet-7340/pic/memes/" + enc(name) + ".png"
                    + prefs.tokenQuery();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            byte[] data = readBytes(c.getInputStream());
            if (data.length > 512 && f != null) writeBytes(f, data);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 表情包的磁盘缓存路径。名字里有中文，用 md5 当文件名。 */
    public static File memeFile(String name) {
        File d = cacheDir();
        if (d == null) return null;
        File m = new File(d, "memes");
        if (!m.exists()) m.mkdirs();
        return new File(m, AnimLib.keyOf(name) + ".png");
    }

    private static void writeFile(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static void writeBytes(File f, byte[] data) {
        try {
            File tmp = new File(f.getAbsolutePath() + ".part");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(data);
            out.close();
            tmp.renameTo(f);
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File f) throws Exception {
        return new String(readBytes(new FileInputStream(f)), "UTF-8");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        return new String(readBytes(in), "UTF-8");
    }
}
