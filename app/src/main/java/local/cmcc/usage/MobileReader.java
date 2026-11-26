package local.cmcc.usage;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class MobileReader extends AccessibilityService {
    static final Uri STATUS_URI = Snapshot.URI.buildUpon().path("reader-status").build();
    private static final String TAG = "CmccUsagePanel";
    private static final int READ_DELAY_MS = 180;
    private static final int[] RETRY_DELAY_MS = {350, 850, 1800};
    private static final int MAX_NODES = 900;
    private static final int MAX_TEXT_ITEMS = 550;
    private static final int MAX_CHILD_WINDOWS = 4;
    private static final EnumMap<Carrier, String> failures = new EnumMap<>(Carrier.class);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable read = this::readCarrierPage;
    private final QueryVisit telecom = new QueryVisit("com.ct.client.",
            "com.ct.client.activity.MainActivity", "com.ct.client.selfservice5.activity.MealAllowanceActivityBlue",
            "com.ct.client.selfservice5.activity.BillBalanceActivity");
    private final QueryVisit unicom = new QueryVisit("com.sinovatech.unicom.",
            "com.sinovatech.unicom.basic.ui.activity.MainActivity",
            "com.sinovatech.unicom.basic.ui.activity.WebDetailActivity");
    private Carrier requestedCarrier;
    private boolean retryPending;
    private int nextRetry;
    private int trafficWindow = -1;

    private static final class PageNodes {
        final List<String> text = new ArrayList<>();
        int visited;
        int invisible;

        boolean hasRoom() {
            return visited < MAX_NODES && text.size() < MAX_TEXT_ITEMS;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        failures.clear();
        Log.i(TAG, "读取服务已连接");
        AccessibilityServiceInfo info = getServiceInfo();
        ReaderLog.record(this, ReaderLog.Stage.SERVICE, "读取服务已连接，窗口列表 "
                + (info != null && (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0)
                + "，布局节点 "
                + (info != null && (info.flags & AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0)
                + "，读取窗口内容 "
                + (info != null && (info.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0));
        getContentResolver().notifyChange(STATUS_URI, null, 0);
        schedule(null);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        ReaderLog.record(this, ReaderLog.Stage.SERVICE, "读取服务已断开");
        onInterrupt();
        getContentResolver().notifyChange(STATUS_URI, null, 0);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Carrier carrier = event == null ? null : Carrier.forPackage(event.getPackageName());
        if (carrier == null) return;
        QueryVisit visit = visitFor(carrier);
        int eventType = event.getEventType();
        if (requestedCarrier != carrier || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ReaderLog.record(this, ReaderLog.Stage.EVENT, carrier.label + " 收到页面事件 "
                    + AccessibilityEvent.eventTypeToString(eventType) + "，窗口 " + event.getWindowId());
        }
        if (visit != null && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            visit.windowChanged(event.getClassName());
        }
        schedule(carrier);
    }

    private void schedule(Carrier carrier) {
        requestedCarrier = carrier;
        if (!retryPending && handler.hasCallbacks(read)) return;
        // WebView 有新事件时提前读取，页面加载未发事件时也会补读几次。
        handler.removeCallbacks(read);
        retryPending = false;
        nextRetry = 0;
        handler.postDelayed(read, READ_DELAY_MS);
    }

    private boolean retry() {
        if (nextRetry == RETRY_DELAY_MS.length) return false;
        retryPending = true;
        handler.postDelayed(read, RETRY_DELAY_MS[nextRetry++]);
        return true;
    }

    private void readCarrierPage() {
        retryPending = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            Carrier foreground = Carrier.forPackage(root.getPackageName());
            if (foreground != null || requestedCarrier != null) {
                ReaderLog.record(this, ReaderLog.Stage.WINDOW, foreground == null ? "前台已切到其他应用"
                        : foreground.label + " 活动窗口节点可用，窗口 " + root.getWindowId());
            }
            readRoot(root, foreground);
        } else if (requestedCarrier != null) {
            readInteractiveWindow();
        } else {
            ReaderLog.record(this, ReaderLog.Stage.WINDOW, "窗口节点暂不可用，尚未收到运营商页面事件");
        }
    }

    private void readInteractiveWindow() {
        // 活动根节点缺失时，按系统窗口列表确定前台窗口，再请求这个窗口的根节点。
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            AccessibilityWindowInfo foreground = foregroundWindow(windows);
            String detail = requestedCarrier.label + " 活动窗口节点为空，系统窗口数 " + windows.size();
            if (foreground == null) {
                ReaderLog.record(this, ReaderLog.Stage.WINDOW, detail + "，无前台窗口");
                if (!retry()) reportFailure(requestedCarrier, "系统未报告前台窗口");
                return;
            }
            detail += "，前台窗口 " + foreground.getId();
            if (foreground.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                ReaderLog.record(this, ReaderLog.Stage.WINDOW, detail + "，窗口类型 " + foreground.getType());
                retryWindowSwitch();
                return;
            }
            AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= 33 ? foreground.getRoot(0) : foreground.getRoot();
            if (root == null) {
                ReaderLog.record(this, ReaderLog.Stage.WINDOW, detail + "，按窗口读取仍缺少内容");
                if (!retry()) reportFailure(requestedCarrier, "系统未提供前台页面内容");
                return;
            }
            // 列表和根节点分两次返回，核对窗口和运营商再读节点，避免在切页时读到另一窗口。
            Carrier carrier = Carrier.forPackage(root.getPackageName());
            if (root.getWindowId() != foreground.getId() || carrier != requestedCarrier) {
                ReaderLog.record(this, ReaderLog.Stage.WINDOW, detail + "，前台窗口或运营商已变化");
                recycleLegacyNode(root);
                retryWindowSwitch();
                return;
            }
            ReaderLog.record(this, ReaderLog.Stage.WINDOW, detail + "，按窗口读取成功");
            readRoot(root, carrier);
        } finally {
            recycleLegacyWindows(windows);
        }
    }

    private static AccessibilityWindowInfo foregroundWindow(List<AccessibilityWindowInfo> windows) {
        AccessibilityWindowInfo focused = null;
        for (AccessibilityWindowInfo window : windows) {
            if (window.isActive()) return window;
            if (focused == null && window.isFocused()) focused = window;
        }
        return focused;
    }

    private void retryWindowSwitch() {
        // 切页动画期间前台窗口可能还没有更新，给它一次切换时间。
        if (requestedCarrier != null && nextRetry == 0) retry();
    }

    private void readRoot(AccessibilityNodeInfo root, Carrier carrier) {
        try {
            if (root.getWindowId() != trafficWindow) trafficWindow = -1;
            if (carrier == null) {
                retryWindowSwitch();
                return;
            }
            requestedCarrier = carrier;
            List<MobileSim.Card> cards = MobileSim.active(this);
            if (cards.stream().noneMatch(card -> card.carrier == carrier && card.number != null)) {
                reportFailure(carrier, "读取服务暂未取得本机 SIM 号码");
                return;
            }
            PageNodes nodes = new PageNodes();
            collect(root, nodes, 0, carrier == Carrier.MOBILE, false);
            List<String> text = nodes.text;
            int window = root.getWindowId();
            QueryVisit visit = visitFor(carrier);
            if (visit != null) {
                String homeAccount = CarrierReadings.homeAccount(carrier, text);
                if (homeAccount != null) {
                    ReaderLog.record(this, ReaderLog.Stage.QUERY, carrier.label + " 已识别首页号码");
                    visit.seeHomeAccount(window, homeAccount);
                    return;
                }
            }
            CarrierReadings readings = CarrierReadings.parse(carrier, text);
            String mobilePage = readings == null && carrier == Carrier.MOBILE
                    ? CarrierReadings.mobileQueryTitle(text) : null;
            if (mobilePage != null) readings = readMobileBody(root, text, mobilePage);
            if (readings == null) {
                String reason = text.isEmpty() ? " 页面无可见文本" : " 未识别到查询页汇总";
                String evidence = carrier == Carrier.MOBILE && !text.isEmpty()
                        ? "，" + CarrierReadings.mobileEvidence(text) : "";
                ReaderLog.record(this, ReaderLog.Stage.QUERY, carrier.label + " 窗口 " + window + reason + evidence);
                if (!retry()) reportFailure(carrier,
                        mobilePage == null ? "暂未读到查询页内容" : "系统暂未提供查询页正文");
                return;
            }
            boolean traffic = readings.trafficValue != null;
            String account = readings.account;
            if (visit != null) {
                if (account == null) account = visit.queryAccount(window, traffic);
                else visit.clear();
            }
            MobileSim.Card sim = MobileSim.match(cards, carrier, account);
            int seenWindow = traffic ? window : -1;
            String query = carrier.label + (traffic ? " 流量查询" : " 余额查询");
            if (sim != null) {
                Snapshot.save(this, sim, readings, seenWindow != trafficWindow);
                trafficWindow = seenWindow;
                ReaderLog.record(this, ReaderLog.Stage.QUERY, query + " 已匹配本机 SIM，读数可用");
                reportFailure(carrier, null);
            } else {
                String reason = account == null ? "暂未读到查询页号码" : "查询页号码无法匹配本机 SIM";
                ReaderLog.record(this, ReaderLog.Stage.QUERY, query + " " + reason);
                if (!retry()) reportFailure(carrier, reason);
            }
        } finally {
            recycleLegacyNode(root);
        }
    }

    private CarrierReadings readMobileBody(AccessibilityNodeInfo root, List<String> header, String page) {
        PageNodes expanded = new PageNodes();
        collect(root, expanded, 0, true, true);
        CarrierReadings readings = parseMobileBody(page, expanded.text);
        String detail = "中国移动 窗口 " + root.getWindowId() + " " + page
                + " 遍历 " + expanded.visited + " 个节点，不可见容器 " + expanded.invisible
                + "，可见文本 " + expanded.text.size();
        if (readings != null) {
            ReaderLog.record(this, ReaderLog.Stage.BODY, detail + "，读到正文");
            return readings;
        }
        AccessibilityWindowInfo window = root.getWindow();
        int childCount = 0;
        if (window != null) {
            try {
                if (window.getId() == root.getWindowId() && (window.isActive() || window.isFocused())) {
                    childCount = window.getChildCount();
                    readings = readLinkedMobileBody(window, header, page);
                }
            } finally {
                recycleLegacyWindow(window);
            }
        }
        ReaderLog.record(this, ReaderLog.Stage.BODY, detail + "，关联子窗口 " + childCount
                + (readings == null ? "，正文未取得" : "，从关联窗口读到正文"));
        return readings;
    }

    private CarrierReadings readLinkedMobileBody(AccessibilityWindowInfo window, List<String> header, String page) {
        Rect foregroundBounds = new Rect();
        Rect childBounds = new Rect();
        window.getBoundsInScreen(foregroundBounds);
        int limit = Math.min(window.getChildCount(), MAX_CHILD_WINDOWS);
        for (int index = 0; index < limit; index++) {
            AccessibilityWindowInfo child = window.getChild(index);
            if (child == null) continue;
            try {
                // 只尝试与当前查询页直接关联且屏幕位置有重叠的应用子窗口。
                child.getBoundsInScreen(childBounds);
                if (child.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || child.getDisplayId() != window.getDisplayId()
                        || !Rect.intersects(foregroundBounds, childBounds)) continue;
                AccessibilityNodeInfo body = Build.VERSION.SDK_INT >= 33 ? child.getRoot(0) : child.getRoot();
                if (body == null) continue;
                try {
                    if (body.getWindowId() != child.getId()
                            || Carrier.forPackage(body.getPackageName()) != Carrier.MOBILE) continue;
                    PageNodes nodes = new PageNodes();
                    nodes.text.addAll(header);
                    collect(body, nodes, 0, true, true);
                    CarrierReadings readings = parseMobileBody(page, nodes.text);
                    if (readings != null) return readings;
                } finally {
                    recycleLegacyNode(body);
                }
            } finally {
                recycleLegacyWindow(child);
            }
        }
        return null;
    }

    private static CarrierReadings parseMobileBody(String page, List<String> text) {
        return page.equals(CarrierReadings.mobileQueryTitle(text)) ? CarrierReadings.parse(Carrier.MOBILE, text) : null;
    }

    static String lastFailure(Carrier carrier) {
        return failures.get(carrier);
    }

    private void reportFailure(Carrier carrier, String reason) {
        // 状态只保留到服务重连，日志不包含页面内容和号码。
        String previous = reason == null ? failures.remove(carrier) : failures.put(carrier, reason);
        if (Objects.equals(previous, reason)) return;
        if (reason != null) {
            Log.i(TAG, carrier.label + " " + reason);
            ReaderLog.record(this, ReaderLog.Stage.QUERY, carrier.label + " 读取失败，" + reason);
        }
        getContentResolver().notifyChange(STATUS_URI, null, 0);
    }

    private QueryVisit visitFor(Carrier carrier) {
        return switch (carrier) {
            case TELECOM -> telecom;
            case UNICOM -> unicom;
            case MOBILE -> null;
        };
    }

    private void collect(AccessibilityNodeInfo node, PageNodes page, int depth, boolean mobile, boolean expanded) {
        if (depth > 45 || !page.hasRoom()) return;
        page.visited++;
        CharSequence source = expanded ? node.getPackageName() : null;
        if (source != null && Carrier.forPackage(source) != Carrier.MOBILE) return;
        if (node.isVisibleToUser()) {
            if (!expanded || source != null) addVisibleText(node, page.text, mobile);
        } else {
            if (!expanded) return;
            // 嵌入式页面的容器可能标为不可见，仍检查其子节点，只采纳实际可见的文字。
            page.invisible++;
        }
        for (int index = 0; index < node.getChildCount() && page.hasRoom(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                collect(child, page, depth + 1, mobile, expanded);
            } finally {
                recycleLegacyNode(child);
            }
        }
    }

    private static void addVisibleText(AccessibilityNodeInfo node, List<String> text, boolean mobile) {
        if (mobile) {
            // 查询页标题也可能由可访问区域上报，读取范围仍限可见的查询页。
            CharSequence title = node.getPaneTitle();
            if (title != null && (Carrier.MOBILE.trafficPage.contentEquals(title)
                    || Carrier.MOBILE.balancePage.contentEquals(title))) {
                add(text, title);
            }
        }
        add(text, node.getText());
        add(text, node.getContentDescription());
    }

    @SuppressWarnings("deprecation")
    private static void recycleLegacyNode(AccessibilityNodeInfo node) {
        // Android 13 移除了节点对象池，旧系统仍需要归还节点。
        if (Build.VERSION.SDK_INT < 33) node.recycle();
    }

    private static void recycleLegacyWindows(List<AccessibilityWindowInfo> windows) {
        for (AccessibilityWindowInfo window : windows) recycleLegacyWindow(window);
    }

    @SuppressWarnings("deprecation")
    private static void recycleLegacyWindow(AccessibilityWindowInfo window) {
        if (Build.VERSION.SDK_INT < 33) window.recycle();
    }

    private static void add(List<String> text, CharSequence value) {
        if (value != null && value.length() > 0) text.add(value.toString());
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(read);
        requestedCarrier = null;
        retryPending = false;
        nextRetry = 0;
        telecom.clear();
        unicom.clear();
    }

    @Override
    public void onDestroy() {
        onInterrupt();
        super.onDestroy();
    }
}
