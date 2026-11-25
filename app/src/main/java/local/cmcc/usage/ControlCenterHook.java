package local.cmcc.usage;

import android.content.ContentProvider;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.util.Log;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ControlCenterHook implements IXposedHookLoadPackage {
    static final String SECURITY_CENTER = "com.miui.securitycenter";
    static final Uri PANEL = Uri.parse("content://com.miui.networkassistant.provider/bill_traffic_inquire");
    private static final String TAG = "CmccUsagePanel";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!SECURITY_CENTER.equals(load.packageName)) return;
        // 不同澎湃版本会把手机管家 Provider 放在主进程或独立的 remote 进程。
        if (!SECURITY_CENTER.equals(load.processName)
                && !(SECURITY_CENTER + ".remote").equals(load.processName)) return;
        try {
            XposedHelpers.findAndHookMethod("com.miui.networkassistant.provider.NetworkAssistantProvider",
                    load.classLoader, "query", Uri.class, String[].class, String.class, String[].class,
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam call) {
                            Object input = call.getResult();
                            if (!(input instanceof Cursor) || !PANEL.equals(call.args[0])) return;
                            ContentProvider provider = (ContentProvider) call.thisObject;
                            fillPanel(provider, (Cursor) input, call);
                        }
                    });
            Log.i(TAG, "控制中心余量入口已接入");
        } catch (RuntimeException | LinkageError failure) {
            Log.w(TAG, "本机手机管家的余量入口不同，保留原生结果", failure);
        }
    }

    private static void fillPanel(ContentProvider provider, Cursor original, XC_MethodHook.MethodHookParam call) {
        Context context = provider.getContext();
        if (context == null) return;
        boolean systemUi = "com.android.systemui".equals(provider.getCallingPackage());
        long identity = Binder.clearCallingIdentity();
        try (Cursor value = context.getContentResolver().query(Snapshot.URI, null, null, null, null)) {
            if (value == null) return;
            Map<String, PanelReading> readings = new HashMap<>();
            int subscriptionColumn = value.getColumnIndex("subscription_id");
            int timeColumn = value.getColumnIndex("carrier_read_at");
            int totalColumn = value.getColumnIndex("carrier_total_bytes");
            int usedColumn = value.getColumnIndex("carrier_used_bytes");
            boolean showingNativeUsage = false;
            while (value.moveToNext()) {
                String traffic = value.isNull(1) ? null : value.getString(1);
                String unit = value.isNull(2) ? null : value.getString(2);
                String balance = value.isNull(3) ? null
                        : BigDecimal.valueOf(value.getLong(3), 2).toPlainString();
                String[] system = timeColumn < 0 || totalColumn < 0 || usedColumn < 0 || subscriptionColumn < 0
                        ? null : NativeTraffic.read(provider, context, value.getInt(0), value.getInt(subscriptionColumn),
                                value.getLong(timeColumn), value.getLong(totalColumn), value.getLong(usedColumn));
                if (system != null) showingNativeUsage = true;
                readings.put(value.getString(0), system == null ? new PanelReading(traffic, unit, "1", balance, false)
                        : new PanelReading(system[0], system[1], system[2], balance, true));
            }
            if (readings.isEmpty()) return;
            if (systemUi && showingNativeUsage) NativeTraffic.refreshVisiblePanel(context);
            MatrixCursor result = mergePanel(original, readings);
            if (result != null) {
                call.setResult(result);
                original.close();
            }
        } catch (RuntimeException | LinkageError failure) {
            Log.w(TAG, "余量暂时不可用，保留原生结果", failure);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static MatrixCursor mergePanel(Cursor original, Map<String, PanelReading> readings) {
        int slotColumn = original.getColumnIndexOrThrow("sim_slot");
        int trafficType = original.getColumnIndexOrThrow("traffic_type");
        int trafficValue = original.getColumnIndexOrThrow("traffic_value");
        int trafficUnit = original.getColumnIndexOrThrow("traffic_unit");
        int billType = original.getColumnIndexOrThrow("bill_type");
        int billValue = original.getColumnIndexOrThrow("bill_value");

        int position = original.getPosition();
        MatrixCursor copy = new MatrixCursor(original.getColumnNames(), original.getCount());
        boolean changed = false;
        try {
            original.moveToPosition(-1);
            while (original.moveToNext()) {
                String[] row = new String[copy.getColumnCount()];
                for (int index = 0; index < row.length; index++) {
                    row[index] = original.isNull(index) ? null : original.getString(index);
                }
                // 已接入原生校正的卡用系统读数，等待校正时用最近一次运营商快照补齐空值。
                PanelReading reading = readings.get(row[slotColumn]);
                if (reading != null) {
                    if (reading.traffic != null && reading.unit != null
                            && (reading.system || row[trafficValue] == null || row[trafficValue].isEmpty()
                                || row[trafficType] == null || "-1".equals(row[trafficType]))) {
                        row[trafficType] = reading.type;
                        row[trafficValue] = reading.traffic;
                        row[trafficUnit] = reading.unit;
                        changed = true;
                    }
                    if (reading.balance != null
                            && (!reading.balance.equals(row[billValue]) || !"0".equals(row[billType]))) {
                        row[billType] = "0";
                        row[billValue] = reading.balance;
                        changed = true;
                    }
                }
                copy.addRow(row);
            }
        } finally {
            original.moveToPosition(position);
        }
        if (changed) return copy;
        copy.close();
        return null;
    }

    private static final class PanelReading {
        final String traffic;
        final String unit;
        final String type;
        final String balance;
        final boolean system;

        PanelReading(String traffic, String unit, String type, String balance, boolean system) {
            this.traffic = traffic;
            this.unit = unit;
            this.type = type;
            this.balance = balance;
            this.system = system;
        }
    }
}
