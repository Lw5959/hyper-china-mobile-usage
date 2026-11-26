package local.cmcc.usage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;

final class ReaderLog {
    enum Stage { SERVICE, EVENT, WINDOW, QUERY, BODY, SETUP }

    static final String MIME_TYPE = "text/plain";
    private static final int HISTORY_LIMIT = 20_000;
    private static final long REPEAT_INTERVAL_MS = 10_000;
    private static final String HISTORY_KEY = "history";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern(
            "yyyyMMdd-HHmmss-SSS", Locale.ROOT);
    private static final EnumMap<Stage, LastEntry> lastEntries = new EnumMap<>(Stage.class);

    private record LastEntry(String message, long elapsed) {}

    private ReaderLog() {}

    static synchronized void record(Context context, Stage stage, String message) {
        long elapsed = SystemClock.elapsedRealtime();
        LastEntry previous = lastEntries.get(stage);
        if (previous != null && previous.message.equals(message)
                && elapsed - previous.elapsed < REPEAT_INTERVAL_MS) return;
        lastEntries.put(stage, new LastEntry(message, elapsed));
        SharedPreferences prefs = preferences(context);
        // 只保存调用方生成的状态和计数。按整行截去过早的事件，避免诊断产生大文件。
        String history = prefs.getString(HISTORY_KEY, "") + OffsetDateTime.now().format(TIMESTAMP)
                + "  " + message + "\n";
        if (history.length() > HISTORY_LIMIT) {
            history = history.substring(history.indexOf('\n', history.length() - HISTORY_LIMIT) + 1);
        }
        prefs.edit().putString(HISTORY_KEY, history).apply();
    }

    static Uri export(Context context, Carrier carrier) throws IOException {
        OffsetDateTime now = OffsetDateTime.now();
        String report = "澎湃 SIM 卡余量查询 读取日志\n"
                + "应用版本 " + versionOf(context, context.getPackageName()) + "\n"
                + "设备 " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "系统版本 " + Build.DISPLAY + " / " + Build.VERSION.INCREMENTAL + "\n"
                + (carrier == null ? "" : carrier.label + " App " + versionOf(context, carrier.packageName) + "\n")
                + "导出时间 " + now.format(TIMESTAMP) + "\n\n"
                + preferences(context).getString(HISTORY_KEY, "暂无读取记录\n");
        ContentResolver resolver = context.getContentResolver();
        ContentValues item = new ContentValues();
        item.put(MediaStore.MediaColumns.DISPLAY_NAME, "sim-usage-reader-" + now.format(FILE_TIMESTAMP) + ".txt");
        item.put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE);
        item.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        item.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri file = resolver.insert(downloads, item);
        if (file == null) throw new IOException("无法创建日志文件");
        try {
            try (OutputStream output = resolver.openOutputStream(file)) {
                if (output == null) throw new IOException("无法打开日志文件");
                output.write(report.getBytes(StandardCharsets.UTF_8));
            }
            // 完整写入后再显示并分享文件，失败时收回本次创建的条目。
            item.clear();
            item.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(file, item, null, null) != 1) throw new IOException("无法发布日志文件");
            return file;
        } catch (IOException | RuntimeException failure) {
            try {
                resolver.delete(file, null, null);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("reader_log", Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private static String versionOf(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "无法取得版本";
        }
    }
}
