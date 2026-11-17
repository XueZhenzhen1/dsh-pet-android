package local.dsh.pet;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍守护（可选，默认不启用）。
 *
 * 它不看屏幕内容（配置里 canRetrieveWindowContent=false），只用系统绑定它这件事：
 * 被系统绑定的服务很难被国产 ROM 的清理器杀掉，进程活着就能把悬浮球拉回来。
 *
 * 每 30 秒看一眼：用户想要悬浮球 + 有权限 + 服务没在跑 → 拉起来。
 * 这就是用户说的「用无障碍保活」——比起申请一堆乱七八糟的权限，这条路最干净，
 * 代价是设置里会显示「已开启无障碍」，所以做成开关，用户自己决定。
 */
public class PetGuard extends AccessibilityService {

    private static final long TICK = 30_000L;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            try {
                if (Keeper.shouldRun(getApplicationContext())) {
                    Keeper.startOverlay(getApplicationContext(), "guard");
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, TICK);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ui.removeCallbacks(check);
        ui.postDelayed(check, 3000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不读事件：这里只要「被系统绑着」这一件事
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        ui.removeCallbacks(check);
        return super.onUnbind(intent);
    }
}
