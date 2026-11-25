package local.cmcc.usage;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public final class MobileReader extends AccessibilityService {
    static final Uri STATUS_URI = Snapshot.URI.buildUpon().path("reader-status").build();
    private static final String TAG = "CmccUsagePanel";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable read = this::readCarrierPage;
    private final QueryVisit telecom = new QueryVisit("com.ct.client.",
            "com.ct.client.activity.MainActivity", "com.ct.client.selfservice5.activity.MealAllowanceActivityBlue",
            "com.ct.client.selfservice5.activity.BillBalanceActivity");
    private final QueryVisit unicom = new QueryVisit("com.sinovatech.unicom.",
            "com.sinovatech.unicom.basic.ui.activity.MainActivity",
            "com.sinovatech.unicom.basic.ui.activity.WebDetailActivity");
    private boolean scheduled;
    private int trafficWindow = -1;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "读取服务已连接");
        getContentResolver().notifyChange(STATUS_URI, null, 0);
        schedule();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        onInterrupt();
        getContentResolver().notifyChange(STATUS_URI, null, 0);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Carrier carrier = event == null ? null : Carrier.forPackage(event.getPackageName());
        if (carrier == null) return;
        QueryVisit visit = visitFor(carrier);
        if (visit != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            visit.windowChanged(event.getClassName());
        }
        schedule();
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(read, 400);
    }

    private void readCarrierPage() {
        scheduled = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            trace("无活动窗口");
            return;
        }
        try {
            if (root.getWindowId() != trafficWindow) trafficWindow = -1;
            Carrier carrier = Carrier.forPackage(root.getPackageName());
            if (carrier == null) return;
            List<MobileSim.Card> cards = MobileSim.active(this);
            if (cards.stream().noneMatch(card -> card.carrier == carrier && card.number != null)) return;
            List<String> text = new ArrayList<>();
            collect(root, text, 0);
            int window = root.getWindowId();
            QueryVisit visit = visitFor(carrier);
            if (visit != null) {
                String homeAccount = CarrierReadings.homeAccount(carrier, text);
                if (homeAccount != null) {
                    visit.seeHomeAccount(window, homeAccount);
                    return;
                }
            }
            CarrierReadings readings = CarrierReadings.parse(carrier, text);
            if (readings == null) {
                trace(carrier.label + "查询页读数未就绪，节点数=" + text.size());
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
            if (sim != null) {
                Snapshot.save(this, sim, readings, seenWindow != trafficWindow);
                trafficWindow = seenWindow;
            } else {
                trace(carrier.label + (traffic ? "流量" : "话费") + "查询账号未匹配本机 SIM");
            }
        } finally {
            recycleLegacyNode(root);
        }
    }

    private static void trace(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message);
    }

    private QueryVisit visitFor(Carrier carrier) {
        return switch (carrier) {
            case TELECOM -> telecom;
            case UNICOM -> unicom;
            case MOBILE -> null;
        };
    }

    private void collect(AccessibilityNodeInfo node, List<String> text, int depth) {
        if (depth > 45 || text.size() >= 550 || !node.isVisibleToUser()) return;
        add(text, node.getText());
        add(text, node.getContentDescription());
        for (int index = 0; index < node.getChildCount() && text.size() < 550; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                collect(child, text, depth + 1);
            } finally {
                recycleLegacyNode(child);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void recycleLegacyNode(AccessibilityNodeInfo node) {
        // Android 13 移除了节点对象池，旧系统仍需要归还节点。
        if (Build.VERSION.SDK_INT < 33) node.recycle();
    }

    private static void add(List<String> text, CharSequence value) {
        if (value != null && value.length() > 0) text.add(value.toString());
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(read);
        scheduled = false;
        telecom.clear();
        unicom.clear();
    }

    @Override
    public void onDestroy() {
        onInterrupt();
        super.onDestroy();
    }
}
