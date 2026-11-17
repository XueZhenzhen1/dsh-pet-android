package local.dsh.pet;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * 15 分钟一次的看门狗：悬浮球不在了就拉回来。
 *
 * 为什么用 JobScheduler 而不是自己起个循环线程：
 * ① 循环线程本身就会被省电策略清掉，等于让保安也睡觉；
 * ② 系统执行作业时会附带一次「允许启动前台服务」的豁免，
 *    这是 Android 12 之后后台把悬浮层重新拉起来最稳的一条路。
 */
public class PetWatchdog extends JobService {

    private static final String TAG = "PetWatchdog";

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (Keeper.shouldRun(this)) {
                Log.i(TAG, "overlay 不在了，拉起");
                Keeper.startOverlay(this, "watchdog");
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchdog fail: " + t);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // 被系统打断就重排，下一次继续看
    }
}
