package local.cmcc.usage;

import android.content.ContentProvider;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.SparseLongArray;

import de.robv.android.xposed.XposedHelpers;

final class NativeTraffic {
    private static final String TAG = "CmccUsagePanel";
    private static final long MAX_PUBLISH_DELAY_MS = 20_000;
    private static final long REFRESH_INTERVAL_MS = 5_000;
    private static final SparseLongArray lastPublished = new SparseLongArray();
    private static Handler handler;
    private static boolean refreshPending;
    private static boolean failureLogged;

    static synchronized String[] read(ContentProvider provider, Context context, int slot, int subscriptionId,
                                      long capturedAt, long total, long used) {
        if (total <= 0 || used < 0 || used > total || capturedAt <= 0
                || (slot != 0 && slot != 1) || SubscriptionManager.getSlotIndex(subscriptionId) != slot) return null;
        try {
            Class<?> simClass = XposedHelpers.findClass("com.miui.networkassistant.config.SimUserInfo",
                    provider.getClass().getClassLoader());
            Object sim = XposedHelpers.callStaticMethod(simClass, "getInstance", context, slot);
            if (sim == null || !(boolean) XposedHelpers.callMethod(sim, "isSimInserted")
                    || !(boolean) XposedHelpers.callMethod(sim, "isNormalCardEnable")
                    || !(boolean) XposedHelpers.callMethod(sim, "isTrafficManageControlEnable")) return null;
            Object manager = XposedHelpers.getObjectField(provider, "mTrafficManageBinder");
            if (manager == null) return null;
            long systemTotal = (long) XposedHelpers.callMethod(manager, "getCurrentMonthTotalPackage", slot);

            long now = System.currentTimeMillis();
            long correctedAt = (long) XposedHelpers.callMethod(sim, "getDataUsageCorrectedTime");
            if (capturedAt > now) return null;
            if (lastPublished.get(subscriptionId) < capturedAt
                    && (systemTotal != total || correctedAt < capturedAt || correctedAt > now)) {
                if (now - capturedAt > MAX_PUBLISH_DELAY_MS) return null;
                if (used == total && (boolean) XposedHelpers.callMethod(sim, "isDataUsageOverLimitStopNetwork")) {
                    return null;
                }
                if (systemTotal == total) {
                    XposedHelpers.callMethod(manager, "manualCorrectNormalDataUsage", used, slot);
                } else {
                    if (!correctPackage(simClass.getClassLoader(), sim, manager, slot, total, used)) return null;
                    systemTotal = (long) XposedHelpers.callMethod(manager, "getCurrentMonthTotalPackage", slot);
                    if (systemTotal != total) return null;
                }
                lastPublished.put(subscriptionId, capturedAt);
                Log.i(TAG, "SIM " + (slot + 1) + " 流量基准已同步至手机管家");
                notifyPanel(context);
            }
            if (systemTotal != total) return null;

            // 小米控制中心会清空离线运营商的行，恢复这张卡由手机管家计算的原生结果。
            String[] result = (String[]) XposedHelpers.callMethod(provider, "getTrafficDetails", sim, context, -1);
            if (result == null || result.length < 3 || result[0] == null || result[0].isEmpty()
                    || (!"1".equals(result[2]) && !"0".equals(result[2]))) return null;
            return result;
        } catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError failure) {
            logFailure(failure);
            return null;
        }
    }

    private static boolean correctPackage(ClassLoader loader, Object sim, Object manager, int slot,
                                          long total, long used) {
        long overlay = 0;
        long overlayTime = (long) XposedHelpers.callMethod(sim, "getDataUsageOverlayPackageTime");
        if (overlayTime > 0) {
            Class<?> dateUtil = XposedHelpers.findClass("com.miui.networkassistant.utils.DateUtil", loader);
            if ((boolean) XposedHelpers.callStaticMethod(dateUtil, "isCurrentCycleMonth", overlayTime)) {
                overlay = (long) XposedHelpers.callMethod(sim, "getDataUsageOverlayPackage");
            }
        }
        // 手机管家会另加有效叠加包。提交基础套餐额度，让系统生效的总额等于查询页总额。
        if (overlay < 0 || overlay > total) return false;
        Class<?> statusClass = XposedHelpers.findClass("com.miui.networkassistant.model.TrafficUsedStatus", loader);
        Object status = XposedHelpers.newInstance(statusClass, used, total - used, slot);
        XposedHelpers.callMethod(status, "setTotalTrafficB", total - overlay);
        if (overlay > 0) XposedHelpers.callMethod(status, "setExtraTotalB", overlay);
        // 用手机管家的联合校正入口提交同一次查询结果，由其按此后的系统用量继续扣减。
        XposedHelpers.callMethod(manager, "applyCorrectedPkgsAndUsageValues", status, true, slot);
        return true;
    }

    static synchronized void refreshVisiblePanel(Context context) {
        if (refreshPending) return;
        if (handler == null) handler = new Handler(context.getMainLooper());
        // 控制中心在监听时才会请求下一次刷新，扣减和计数都由手机管家完成。
        refreshPending = handler.postDelayed(() -> refreshPanelIfInteractive(context), REFRESH_INTERVAL_MS);
    }

    private static void refreshPanelIfInteractive(Context context) {
        synchronized (NativeTraffic.class) {
            refreshPending = false;
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && power.isInteractive()) notifyPanel(context);
    }

    private static void notifyPanel(Context context) {
        try {
            context.getContentResolver().notifyChange(ControlCenterHook.PANEL, null, 0);
        } catch (RuntimeException | LinkageError failure) {
            logFailure(failure);
        }
    }

    private static synchronized void logFailure(Throwable failure) {
        if (failureLogged) return;
        failureLogged = true;
        Log.w(TAG, "手机管家原生流量校正暂不可用", failure);
    }
}
