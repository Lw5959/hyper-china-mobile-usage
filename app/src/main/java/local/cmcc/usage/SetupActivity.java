package local.cmcc.usage;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public final class SetupActivity extends Activity {
    private static final String[] UNITS = {"B", "KB", "MB", "GB"};
    private TextView connection;
    private TextView traffic;
    private TextView cap;
    private TextView trafficTime;
    private TextView balance;
    private TextView balanceTime;
    private Button carrierButton;
    private Button authorize;
    private Button exportLog;
    private Switch displaySwitch;
    private Switch launcherSwitch;
    private List<MobileSim.Card> cards = Collections.emptyList();
    private int selectedSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private Carrier currentCarrier;
    private final ContentObserver readerObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        enableLegacyEdgeToEdge();
        ScrollView viewport = new ScrollView(this);
        viewport.setBackgroundColor(getColor(R.color.canvas));
        viewport.setFillViewport(true);
        viewport.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout content = column();
        int margin = dp(22);
        content.setPadding(margin, dp(20), margin, dp(24));
        viewport.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView title = text(getString(R.string.app_name), 33, R.color.ink);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        content.addView(title);
        connection = text("", 14, R.color.accent);
        connection.setPadding(dp(11), dp(6), dp(11), dp(6));
        connection.setCompoundDrawablePadding(dp(8));
        connection.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000),
                surface(R.color.accent_surface, 15), null));
        connection.setOnClickListener(view -> showNumbers());
        addBelow(content, connection, 10, -2);

        LinearLayout usageCard = card();
        usageCard.addView(text("剩余通用流量", 14, R.color.muted));
        traffic = text("待查询", 37, R.color.ink);
        traffic.setTypeface(null, Typeface.BOLD);
        addBelow(usageCard, traffic, 4, -1);
        cap = text("", 15, R.color.accent);
        addBelow(usageCard, cap, 5, -1);
        trafficTime = text("", 13, R.color.muted);
        addBelow(usageCard, trafficTime, 7, -1);
        addBelow(content, usageCard, 22, -1);

        LinearLayout balanceCard = card();
        balanceCard.addView(text("账户可用余额", 14, R.color.muted));
        balance = text("待查询", 32, R.color.ink);
        balance.setTypeface(null, Typeface.BOLD);
        addBelow(balanceCard, balance, 4, -1);
        balanceTime = text("", 13, R.color.muted);
        addBelow(balanceCard, balanceTime, 7, -1);
        addBelow(content, balanceCard, 11, -1);

        carrierButton = actionButton("", this::openCarrier,
                R.color.accent, R.color.on_accent);
        addBelow(content, carrierButton, 12, -1);
        authorize = actionButton("", this::repairPermissions,
                R.color.accent_surface, R.color.accent);
        addBelow(content, authorize, 7, -1);

        LinearLayout options = card();
        displaySwitch = option("控制中心同步", "同步到「移动数据」，手机管家按系统用量扣减。");
        options.addView(displaySwitch);
        displaySwitch.setOnCheckedChangeListener((button, enabled) -> Snapshot.setPanelEnabled(this, enabled));
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.line));
        LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, dp(1));
        divider.setMargins(0, dp(6), 0, dp(6));
        options.addView(line, divider);
        launcherSwitch = option("隐藏桌面图标", "从模块管理器的「操作」打开。");
        options.addView(launcherSwitch);
        launcherSwitch.setOnCheckedChangeListener((button, hidden) -> toggleLauncher(hidden));
        addBelow(content, options, 17, -1);
        exportLog = actionButton("导出读取日志", this::exportReaderLog, R.color.accent_surface, R.color.accent);
        addBelow(content, exportLog, 12, -1);
        setContentView(viewport);
    }

    @SuppressWarnings("deprecation")
    private void enableLegacyEdgeToEdge() {
        // 目标 API 35 的 Activity 在 Android 15 及更新系统默认采用全面屏布局。
        if (Build.VERSION.SDK_INT < 35) getWindow().setDecorFitsSystemWindows(false);
    }

    private void addBelow(LinearLayout parent, View child, int gap, int width) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, -2);
        layout.topMargin = dp(gap);
        parent.addView(child, layout);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(17), dp(15), dp(17), dp(15));
        card.setBackground(surface(R.color.surface, 20));
        return card;
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        return view;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getColor(color));
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Button actionButton(String label, Runnable click, int background, int foreground) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000),
                surface(background, 14), null));
        button.setTextColor(getColor(foreground));
        button.setStateListAnimator(null);
        button.setMinimumHeight(dp(50));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private Switch option(String title, String description) {
        Switch control = new Switch(this);
        control.setText(android.text.Html.fromHtml(title + "<br><small>" + description + "</small>",
                android.text.Html.FROM_HTML_MODE_COMPACT));
        control.setTextColor(getColor(R.color.ink));
        control.setTextSize(15);
        control.setMinHeight(dp(59));
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setSwitchPadding(dp(12));
        return control;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 系统保存开关和实际绑定服务是两个事件，两者都可能在返回页面后才完成。
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, readerObserver);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, readerObserver);
        getContentResolver().registerContentObserver(MobileReader.STATUS_URI, false, readerObserver);
    }

    @Override
    protected void onStop() {
        getContentResolver().unregisterContentObserver(readerObserver);
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        refresh();
    }

    private void refresh() {
        boolean granted = simPermissionGranted();
        boolean readerEnabled = readerAuthorized();
        boolean reader = readerEnabled && readerConnected();
        cards = granted ? MobileSim.active(this) : Collections.emptyList();
        MobileSim.Card sim = selectedCard();
        currentCarrier = sim == null ? null : sim.carrier;
        String label = !granted ? "识别 SIM 需要电话权限"
                : sim == null ? "未识别到运营商 SIM" : sim.label();
        connection.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, cards.size() > 1 ? R.drawable.ic_sim_dropdown : 0, 0);
        String status = !readerEnabled ? "读取服务未开启"
                : reader ? "读取服务已开启 · 已连接" : "读取服务已开启 · 未连接";
        String failure = reader && sim != null ? MobileReader.lastFailure(sim.carrier) : null;
        if (failure != null) status += "\n" + failure;
        connection.setText(label + "\n" + status);
        connection.setClickable(sim != null);
        carrierButton.setVisibility(currentCarrier == null ? View.GONE : View.VISIBLE);
        carrierButton.setText(currentCarrier == null ? "" : "打开" + currentCarrier.label);
        authorize.setText(!granted ? "授予电话权限"
                : readerEnabled ? "读取服务设置" : "手动开启读取服务");

        Snapshot stored = sim == null ? null : Snapshot.load(this, sim);
        long now = System.currentTimeMillis();
        boolean sameSim = stored != null && stored.matches(sim);
        boolean trafficReady = sameSim && stored.hasTraffic(now);
        boolean balanceReady = sameSim && stored.hasBalance(now);
        traffic.setText(trafficReady ? stored.traffic + " " + UNITS[stored.unit] : "待查询");
        boolean totalReady = sameSim && stored.hasTotal(now);
        cap.setVisibility(totalReady ? View.VISIBLE : View.GONE);
        cap.setText(totalReady ? "总额 " + totalAmount(stored) : "");
        trafficTime.setText(trafficReady ? updateTime(stored.trafficTime)
                : queryHint(sim, currentCarrier == null ? null : currentCarrier.trafficPage));
        balance.setText(balanceReady ? BigDecimal.valueOf(stored.balanceCents, 2).toPlainString() + " 元" : "待查询");
        balanceTime.setText(balanceReady ? updateTime(stored.balanceTime)
                : queryHint(sim, currentCarrier == null ? null : currentCarrier.balancePage));
        displaySwitch.setChecked(Snapshot.panelEnabled(this));
        launcherSwitch.setChecked(launcherHidden());
    }

    private String queryHint(MobileSim.Card sim, String page) {
        if (sim != null && sim.number == null) return "系统暂未提供手机号";
        return page == null ? "" : "打开「" + page + "」";
    }

    private static String totalAmount(Snapshot stored) {
        if (stored.totalBytes < 0 || stored.totalBytes >= (1L << 30)) return stored.totalGb + " GB";
        int unit = stored.totalBytes >= (1L << 20) ? 2 : stored.totalBytes >= (1L << 10) ? 1 : 0;
        return BigDecimal.valueOf(stored.totalBytes).divide(BigDecimal.valueOf(1L << (10 * unit)),
                2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " " + UNITS[unit];
    }

    private String updateTime(long timestamp) {
        Date date = new Date(timestamp);
        return "查询于 " + DateFormat.getDateFormat(this).format(date) + " "
                + DateFormat.getTimeFormat(this).format(date);
    }

    private boolean simPermissionGranted() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED;
    }

    private void repairPermissions() {
        if (!simPermissionGranted()) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS}, 1);
            return;
        }
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        Toast.makeText(this, getString(R.string.reader_settings_hint, getString(R.string.app_name)),
                Toast.LENGTH_LONG).show();
    }

    private MobileSim.Card selectedCard() {
        if (cards.isEmpty()) return null;
        MobileSim.Card initial = cards.get(0);
        int defaultSubscription = SubscriptionManager.getDefaultDataSubscriptionId();
        for (MobileSim.Card card : cards) {
            if (card.subscriptionId == selectedSubscription) return card;
            if (card.subscriptionId == defaultSubscription) initial = card;
        }
        return initial;
    }

    private void showNumbers() {
        MobileSim.Card sim = selectedCard();
        if (sim == null) return;
        if (cards.size() == 1) {
            showNumber(cards.get(0));
            return;
        }
        LinearLayout sheet = numberSheet("选择号码");
        Dialog dialog = sheetDialog(sheet);
        for (MobileSim.Card card : cards) {
            addNumberChoice(sheet, dialog, card.label(), card == sim, () -> {
                selectedSubscription = card.subscriptionId;
                refresh();
            });
        }
        addBelow(sheet, actionButton("查看号码", () -> {
            dialog.dismiss();
            showNumber(sim);
        }, R.color.surface, R.color.accent), 6, -1);
        showSheet(dialog);
    }

    private void showNumber(MobileSim.Card card) {
        LinearLayout sheet = numberSheet(card.carrier.label + "　SIM " + (card.slot + 1));
        Dialog dialog = sheetDialog(sheet);
        addBelow(sheet, text(card.number == null ? "系统暂未提供手机号" : card.number, 19, R.color.ink), 10, -1);
        addBelow(sheet, actionButton("关闭", dialog::dismiss, R.color.accent_surface, R.color.accent), 12, -1);
        showSheet(dialog);
    }

    private LinearLayout numberSheet(String title) {
        LinearLayout sheet = card();
        TextView heading = text(title, 19, R.color.ink);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        sheet.addView(heading);
        return sheet;
    }

    private Dialog sheetDialog(LinearLayout sheet) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(12), dp(8), dp(12), dp(12));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(sheet, new ScrollView.LayoutParams(-1, -2));
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.4f);
        }
        return dialog;
    }

    private static void showSheet(Dialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(-1, -2);
    }

    private void addNumberChoice(LinearLayout sheet, Dialog dialog, String label, boolean selected, Runnable choose) {
        Button row = actionButton(label, () -> {
            dialog.dismiss();
            choose.run();
        }, selected ? R.color.accent_surface : R.color.canvas, selected ? R.color.accent : R.color.ink);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(6), dp(13), dp(6));
        row.setSelected(selected);
        addBelow(sheet, row, 8, -1);
    }

    private boolean readerAuthorized() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName reader = new ComponentName(this, MobileReader.class);
        for (String component : enabled.split(":")) {
            if (reader.equals(ComponentName.unflattenFromString(component))) return true;
        }
        return false;
    }

    private boolean readerConnected() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        ComponentName reader = new ComponentName(this, MobileReader.class);
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (reader.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }

    private ComponentName launcher() {
        return new ComponentName(getPackageName(), getPackageName() + ".Launcher");
    }

    private boolean launcherHidden() {
        return getPackageManager().getComponentEnabledSetting(launcher())
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void toggleLauncher(boolean hidden) {
        // 仅修改 Launcher alias，模块操作仍可直接启动设置 Activity。
        getPackageManager().setComponentEnabledSetting(launcher(),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
    }

    private void openCarrier() {
        if (currentCarrier == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(currentCarrier.packageName);
        if (launch == null) {
            Toast.makeText(this, "请先安装" + currentCarrier.label + " App", Toast.LENGTH_SHORT).show();
            return;
        }
        ReaderLog.record(this, ReaderLog.Stage.SETUP, "从配套应用打开" + currentCarrier.label);
        startActivity(launch);
    }

    private void exportReaderLog() {
        exportLog.setEnabled(false);
        Carrier carrier = currentCarrier;
        ReaderLog.record(this, ReaderLog.Stage.SETUP, "导出时服务已开启 " + readerAuthorized()
                + "，已连接 " + readerConnected() + "，识别到 SIM " + cards.size() + " 张");
        Context context = getApplicationContext();
        new Thread(() -> {
            try {
                Uri file = ReaderLog.export(context, carrier);
                runOnUiThread(() -> showExportedLog(file));
            } catch (IOException | RuntimeException failure) {
                Log.w("CmccUsagePanel", "导出读取日志失败", failure);
                runOnUiThread(() -> showExportedLog(null));
            }
        }, "sim-usage-log-export").start();
    }

    private void showExportedLog(Uri file) {
        if (isFinishing() || isDestroyed()) return;
        exportLog.setEnabled(true);
        if (file == null) {
            Toast.makeText(this, "读取日志导出失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "读取日志已保存到「下载」", Toast.LENGTH_SHORT).show();
        if (!hasWindowFocus()) return;
        Intent share = new Intent(Intent.ACTION_SEND).setType(ReaderLog.MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri("读取日志", file));
        try {
            startActivity(Intent.createChooser(share, "分享读取日志"));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // 无法调起分享时，仍可从「下载」取文件。
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
