package local.cmcc.usage;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
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
    private Button authorize;
    private Switch displaySwitch;
    private Switch launcherSwitch;
    private List<MobileSim.Card> cards = Collections.emptyList();
    private int selectedSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
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

        Button carrier = actionButton("打开中国移动", this::openCarrier,
                R.color.accent, R.color.on_accent);
        addBelow(content, carrier, 12, -1);
        TextView source = text("无障碍读取「余量查询」和「余额查询」。", 13, R.color.muted);
        addBelow(content, source, 5, -1);
        authorize = actionButton("", this::repairPermissions,
                R.color.accent_surface, R.color.accent);
        addBelow(content, authorize, 7, -1);

        LinearLayout options = card();
        displaySwitch = option("控制中心同步", "KernelSU 同步读数，手机管家扣减流量。");
        options.addView(displaySwitch);
        displaySwitch.setOnCheckedChangeListener((button, enabled) -> Snapshot.setPanelEnabled(this, enabled));
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.line));
        LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, dp(1));
        divider.setMargins(0, dp(6), 0, dp(6));
        options.addView(line, divider);
        launcherSwitch = option("隐藏桌面图标", "从 KernelSU 模块的「操作」打开。");
        options.addView(launcherSwitch);
        launcherSwitch.setOnCheckedChangeListener((button, hidden) -> toggleLauncher(hidden));
        addBelow(content, options, 17, -1);
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
        String label = !granted ? "识别 SIM 需要电话权限"
                : sim == null ? "未识别到中国移动 SIM" : "中国移动　" + sim.label();
        String status = !readerEnabled ? "读取服务未开启"
                : reader ? "读取服务已开启 · 已连接" : "读取服务已开启 · 未连接";
        connection.setText(label + "\n" + status);
        connection.setClickable(sim != null);
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
        cap.setText(totalReady ? "总额 " + stored.totalGb + " GB" : "");
        String missing = sim != null && sim.number == null ? "系统暂未提供手机号" : null;
        trafficTime.setText(trafficReady ? updateTime(stored.trafficTime)
                : missing != null ? missing : "打开「余量查询」");
        balance.setText(balanceReady ? BigDecimal.valueOf(stored.balanceCents, 2).toPlainString() + " 元" : "待查询");
        balanceTime.setText(balanceReady ? updateTime(stored.balanceTime)
                : missing != null ? missing : "打开「余额查询」");
        displaySwitch.setChecked(Snapshot.panelEnabled(this));
        launcherSwitch.setChecked(launcherHidden());
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
        Toast.makeText(this, R.string.reader_settings_hint, Toast.LENGTH_LONG).show();
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
        if (cards.size() == 1) {
            showNumber(cards.get(0));
        } else if (!cards.isEmpty()) {
            String[] labels = new String[cards.size()];
            for (int index = 0; index < cards.size(); index++) labels[index] = cards.get(index).label();
            new AlertDialog.Builder(this).setTitle("选择移动卡")
                    .setItems(labels, (dialog, index) -> {
                        MobileSim.Card card = cards.get(index);
                        selectedSubscription = card.subscriptionId;
                        refresh();
                        showNumber(card);
                    }).show();
        }
    }

    private void showNumber(MobileSim.Card card) {
        new AlertDialog.Builder(this).setTitle("中国移动　SIM " + (card.slot + 1))
                .setMessage(card.number == null ? "系统暂未提供手机号" : card.number)
                .setPositiveButton("关闭", null).show();
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
        Intent launch = getPackageManager().getLaunchIntentForPackage(MobileReader.CARRIER_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "请先安装中国移动 App", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
