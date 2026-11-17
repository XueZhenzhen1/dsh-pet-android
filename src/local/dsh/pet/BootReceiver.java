package local.dsh.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 开机自启。
 *
 * 悬浮球是「一直在那儿」的东西，重启后不见了等于没装。这里只做一件事：
 * 用户上次是开着的，就把它开回来。没有悬浮窗权限时直接安静退出——
 * 没有权限去 startForegroundService 只会崩在通知栏里。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        boolean revive = "local.dsh.pet.REVIVE".equals(a);
        if (!revive && !Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        Prefs prefs = new Prefs(ctx);
        if (!prefs.isOverlayDesired()) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return;
        Keeper.startOverlay(ctx, revive ? "revive" : "boot");
        Keeper.scheduleWatchdog(ctx);
    }
}
