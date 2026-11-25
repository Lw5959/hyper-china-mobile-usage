package local.cmcc.usage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Snapshot {
    static final Uri URI = Uri.parse("content://local.cmcc.usage.snapshot/latest");
    static final String[] COLUMNS = {"slot", "traffic", "unit", "balance_cents", "subscription_id",
            "carrier_read_at", "carrier_total_bytes", "carrier_used_bytes"};
    static final String CAPTURE_LOG = "中国移动流量基准已更新";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final long WRITE_INTERVAL_MS = 60_000L;

    final String accountKey;
    final String traffic;
    final int unit;
    final long trafficTime;
    final String totalGb;
    final long totalTime;
    final long totalBytes;
    final long usedBytes;
    final long balanceCents;
    final long balanceTime;

    private Snapshot(SharedPreferences prefs) {
        accountKey = prefs.getString("account_key", "");
        traffic = prefs.getString("traffic", null);
        unit = prefs.getInt("unit", -1);
        trafficTime = prefs.getLong("traffic_time", 0);
        totalGb = prefs.getString("total_gb", null);
        totalTime = prefs.getLong("total_time", 0);
        totalBytes = prefs.getLong("total_bytes", -1);
        usedBytes = prefs.getLong("used_bytes", -1);
        balanceCents = prefs.getLong("balance", 0);
        balanceTime = prefs.getLong("balance_time", 0);
    }

    static Snapshot load(Context context, MobileSim.Card sim) {
        return new Snapshot(preferences(context, sim));
    }

    static synchronized void save(Context context, MobileSim.Card sim, CarrierReadings readings,
                                  boolean newTrafficVisit) {
        long now = System.currentTimeMillis();
        Snapshot previous = load(context, sim);
        boolean sameSim = previous.matches(sim);
        SharedPreferences.Editor edit = preferences(context, sim).edit();
        if (!sameSim) {
            edit.clear().putString("account_key", keyFor(sim.number));
        }
        boolean changed = !sameSim;
        // 同一页面的重复事件沿用原快照，只有重新查询或页面读数变化才提交新的校正值。
        boolean newTraffic = readings.trafficValue != null && (!sameSim || previous.unit != readings.trafficUnit
                || !readings.trafficValue.equals(previous.traffic)
                || readings.totalBytes != previous.totalBytes || readings.usedBytes != previous.usedBytes
                || newTrafficVisit || now < previous.trafficTime);
        if (newTraffic) {
            edit.putString("traffic", readings.trafficValue).putInt("unit", readings.trafficUnit)
                    .putLong("traffic_time", now)
                    .putString("total_gb", readings.totalGb).putLong("total_time", now)
                    .putLong("total_bytes", readings.totalBytes).putLong("used_bytes", readings.usedBytes)
                    .remove("usage_baseline").remove("usage_highest").remove("usage_subscriber");
            changed = true;
        }
        if (readings.balanceCents != null && (!sameSim || previous.balanceCents != readings.balanceCents
                || now - previous.balanceTime >= WRITE_INTERVAL_MS || now < previous.balanceTime)) {
            edit.putLong("balance", readings.balanceCents).putLong("balance_time", now);
            changed = true;
        }
        if (changed) {
            edit.apply();
            android.util.Log.i("CmccUsagePanel", newTraffic ? CAPTURE_LOG : "中国移动读数已更新");
        }
    }

    boolean matches(MobileSim.Card sim) {
        return sim.number != null && accountKey.equals(keyFor(sim.number));
    }

    private static String keyFor(String number) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(number.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    boolean hasTraffic(long now) {
        return traffic != null && unit >= 0 && unit <= 3 && fresh(trafficTime, now);
    }

    boolean hasBalance(long now) {
        return fresh(balanceTime, now);
    }

    boolean hasTotal(long now) {
        return totalGb != null && fresh(totalTime, now);
    }

    private static boolean fresh(long time, long now) {
        return time > 0 && now >= time && now - time <= MAX_AGE_MS;
    }

    static boolean panelEnabled(Context context) {
        return context.getSharedPreferences("display", Context.MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void setPanelEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
    }

    private static SharedPreferences preferences(Context context, MobileSim.Card sim) {
        // 卡换到另一槽时沿用该订阅的缓存，显示位置由当前系统卡槽决定。
        return context.getSharedPreferences("snapshot_" + sim.subscriptionId, Context.MODE_PRIVATE);
    }
}
