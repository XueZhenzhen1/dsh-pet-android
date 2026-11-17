package local.dsh.pet;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 保活的三条腿，都收在这里。
 *
 * 1. 前台服务：正常运行时靠它活着（通知栏那条常驻）。
 * 2. 看门狗：15 分钟一次的系统作业，发现服务不在了就拉起来。
 *    系统作业是「被允许启动前台服务」的少数场景之一，所以这条路不会被 Android 12 的
 *    后台启动限制掐掉。
 * 3. 无障碍守护（可选）：AccessibilityService 由系统绑定，几乎不会被清理。
 *    它每 30 秒看一眼悬浮球还在不在，不在就拉起来。用户不打开也不影响 1 和 2。
 *
 * 另外：用户从最近任务里划掉 App 时（onTaskRemoved），用 AlarmManager 补一次，
 * 这是 MIUI / EMUI 这类 ROM 最常杀掉的时机。
 */
public final class Keeper {

    private static final String TAG = "PetKeeper";
    private static final int JOB_ID = 0x5D5E;

    private Keeper() {
    }

    /** 拉起悬浮球服务。失败不抛，返回是否成功发起。 */
    public static boolean startOverlay(Context ctx, String why) {
        try {
            Intent i = new Intent(ctx, PetService.class);
            i.putExtra("why", why);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Throwable t) {
            // Android 12+ 从后台起前台服务可能被拒（ForegroundServiceStartNotAllowedException）。
            // 这里不崩：下一次看门狗或用户点开 App 会再试一次。
            Log.w(TAG, "start overlay fail(" + why + "): " + t);
            return false;
        }
    }

    /** 排一次 15 分钟周期的看门狗。重复调用无害（同一个 JobId 会覆盖）。 */
    public static void scheduleWatchdog(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, PetWatchdog.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)                     // 重启后仍然有效
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            js.schedule(b.build());
        } catch (Throwable t) {
            Log.w(TAG, "schedule watchdog fail: " + t);
        }
    }

    public static void cancelWatchdog(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignored) {
        }
    }

    /** 现在该不该有悬浮球：用户想要 + 有权限 + 服务没在跑。 */
    public static boolean shouldRun(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        if (!prefs.isOverlayDesired()) return false;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return false;
        return !PetService.RUNNING;
    }

    /** 无障碍守护服务是否已开启。 */
    public static boolean isGuardEnabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            return flat.contains(ctx.getPackageName() + "/" + PetGuard.class.getName())
                    || flat.contains(ctx.getPackageName() + "/.PetGuard");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 电池优化白名单是否已经有我们。 */
    public static boolean isIgnoringBattery(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
