package local.dsh.pet;

import android.content.Context;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实动作库：让手机播出和电脑同一段动画。
 *
 * 为什么不是「手机直接放 webm」：
 *   插件里的动作是 VP9 + alpha 的 webm，桌面靠 Chromium 的 <video> 播。
 *   安卓的 MediaCodec 解 VP9 时不输出 alpha 通道，同一个文件在手机上只能解出一块黑底。
 *   所以素材要在电脑上先转成逐帧带 alpha 的动图 WebP（见 tools/convert-anims.py），
 *   手机用 AnimatedImageDrawable 播——这条路不依赖解码器对 alpha 的支持。
 *
 * 为什么不在 APK 里塞：
 *   114 段动作、平均 10 秒，按图片存是上百 MB。所以走「用哪段下哪段」：
 *   电脑上 /pic/anim/<md5>​.webp 是转好的文件，手机下过一次就存在本地，
 *   第二次开始是本机读文件，不再等网络。
 */
public final class AnimLib {

    private static final String TAG = "AnimLib";
    private static final String DIR = "anim";
    /** 单段动作 4 秒出头，几百 KB。留 80MB 上限，够放一百多段。 */
    private static final long CACHE_LIMIT = 80L * 1024 * 1024;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Set<String> DOWNLOADING = new HashSet<>();
    private static final AtomicBoolean TRIMMED = new AtomicBoolean(false);
    /** 电脑上没装转换好的素材（/pic 返回 404）。给设置页显示一句人话用。 */
    private static volatile boolean SERVER_MISSING = false;

    public interface Cb {
        /** 解码好的动画。失败时 d 为 null。回调在主线程。 */
        void onClip(String name, Drawable d);
    }

    private AnimLib() {
    }

    /** 动作名 → 文件名。两边同一种算法，所以不需要额外的清单文件。 */
    public static String keyOf(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest((name == null ? "" : name).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            // 注意 b[i] 是带符号字节：不 & 0xff 的话负数字节会格式化成 ffffffab
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", b[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((name == null ? "" : name).hashCode());
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(Context ctx, String name) {
        return new File(dir(ctx), keyOf(name) + ".webp");
    }

    /**
     * 电脑上缺素材吗。
     *
     * 缺的情况只有一种：这台电脑还没跑过 tools/convert-anims.py，
     * 或者 dsh-pet 升级后新增了动作。这时手机退回程序化演出，功能不残，只是不像原版。
     */
    public static boolean serverMissing() {
        return SERVER_MISSING;
    }

    public static boolean has(Context ctx, String name) {
        File f = fileOf(ctx, name);
        return f.isFile() && f.length() > 1024;
    }

    /**
     * 解码本机缓存。
     * 必须在后台线程调（WebP 解码要十几毫秒），拿到的 Drawable 回主线程再用。
     */
    public static Drawable open(Context ctx, String name) {
        if (Build.VERSION.SDK_INT < 28) return null;   // AnimatedImageDrawable 从 28 才有
        File f = fileOf(ctx, name);
        if (!f.isFile() || f.length() < 1024) return null;
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(f);
            Drawable d = ImageDecoder.decodeDrawable(src);
            if (d instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) d).setRepeatCount(0);   // 只演一遍，演完回静态立绘
            }
            return d;
        } catch (Throwable t) {
            Log.w(TAG, "decode fail " + name + ": " + t);
            return null;
        }
    }

    /**
     * 有缓存就立刻回调，没有就边下载边回调。
     * 无论如何都会回调一次（失败给 null），调用方据此决定要不要退回程序化演出。
     */
    public static void play(final Context ctx, final Prefs prefs, final String name, final Cb cb) {
        if (name == null || name.isEmpty()) {
            cb.onClip(name, null);
            return;
        }
        if (!has(ctx, name)) {
            download(ctx, prefs, name, new Cb() {
                @Override
                public void onClip(String n, Drawable ignored) {
                    decode(ctx, n, cb);
                }
            });
            return;
        }
        decode(ctx, name, cb);
    }

    private static void decode(final Context ctx, final String name, final Cb cb) {
        POOL.execute(() -> {
            final Drawable d = open(ctx, name);
            UI.post(() -> cb.onClip(name, d));
        });
    }

    /** 下载一段动作到本地。已经在下或已存在就不重复发。 */
    public static void download(final Context ctx, final Prefs prefs, final String name, final Cb cb) {
        final String key = keyOf(name);
        synchronized (DOWNLOADING) {
            if (DOWNLOADING.contains(key)) {
                UI.post(() -> cb.onClip(name, null));
                return;
            }
            DOWNLOADING.add(key);
        }
        POOL.execute(() -> {
            boolean ok = false;
            HttpURLConnection c = null;
            File tmp = null;
            try {
                String url = prefs.baseUrl() + "/dsh-pet-7340/pic/anim/" + key + ".webp"
                        + prefs.tokenQuery();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(15000);
                c.setRequestProperty("accept", "image/webp");
                int code = c.getResponseCode();
                if (code == 200) {
                    tmp = new File(dir(ctx), key + ".part");
                    FileOutputStream out = new FileOutputStream(tmp);
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    File dst = fileOf(ctx, name);
                    if (tmp.renameTo(dst)) {
                        ok = true;
                        trim(ctx);
                    } else {
                        tmp.delete();
                    }
                } else {
                    if (code == 404) SERVER_MISSING = true;
                    Log.w(TAG, "GET " + url + " -> " + code);
                }
            } catch (Exception e) {
                Log.w(TAG, "download fail " + name + ": " + e);
            } finally {
                if (c != null) c.disconnect();
                if (tmp != null && tmp.exists()) tmp.delete();
                synchronized (DOWNLOADING) {
                    DOWNLOADING.remove(key);
                }
                final boolean done = ok;
                UI.post(() -> cb.onClip(name, done ? null : null));
            }
        });
    }

    /**
     * 预取：打开动作列表时先把手边这几段拉下来，等用户点的时候就是本机文件。
     * 只在后台跑，失败就算了——点下去还有程序化演出兜着，不会出现「点了没反应」。
     */
    public static void prefetch(final Context ctx, final Prefs prefs, final java.util.List<String> names,
                                final int limit) {
        if (names == null || names.isEmpty()) return;
        // 流量网络不预取：一段快一兆，开个面板就吃掉几兆太不礼貌。
        // 真要播的时候仍然会下载，只是那次是用户明确点的。
        if (isMetered(ctx)) return;
        final ArrayDeque<String> queue = new ArrayDeque<>();
        for (String n : names) {
            if (queue.size() >= limit) break;
            if (n != null && !n.isEmpty() && !has(ctx, n)) queue.add(n);
        }
        if (queue.isEmpty()) return;
        POOL.execute(() -> {
            while (!queue.isEmpty()) {
                String n = queue.poll();
                downloadSync(ctx, prefs, n);
            }
        });
    }

    private static void downloadSync(Context ctx, Prefs prefs, String name) {
        final Object lock = new Object();
        final boolean[] done = {false};
        download(ctx, prefs, name, new Cb() {
            @Override
            public void onClip(String n, Drawable d) {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long end = System.currentTimeMillis() + 20000;
            while (!done[0]) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(Math.min(left, 500));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static boolean isMetered(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                return cm.isActiveNetworkMetered();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean downloading(String name) {
        synchronized (DOWNLOADING) {
            return DOWNLOADING.contains(keyOf(name));
        }
    }

    /** 缓存超限时按修改时间删最旧的。只在下载成功后偶尔跑。 */
    private static void trim(Context ctx) {
        if (!TRIMMED.compareAndSet(false, true)) return;
        try {
            File[] fs = dir(ctx).listFiles();
            if (fs == null) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= CACHE_LIMIT) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) {
                if (total <= CACHE_LIMIT * 3 / 4) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Exception ignored) {
        } finally {
            TRIMMED.set(false);
        }
    }

    /** 缓存占用，给设置页显示。 */
    public static String usage(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return "0 段";
        long total = 0;
        int n = 0;
        for (File f : fs) {
            if (f.getName().endsWith(".webp")) {
                n++;
                total += f.length();
            }
        }
        return n + " 段 · " + (total / 1024 / 1024) + " MB";
    }

    public static void clear(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }
}
