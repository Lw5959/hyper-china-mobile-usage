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
    static final String CARRIER_PACKAGE = "com.greenpoint.android.mc10086.activity";
    static final Uri STATUS_URI = Snapshot.URI.buildUpon().path("reader-status").build();
    private static final String TAG = "CmccUsagePanel";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable read = this::readCarrierPage;
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
        if (event != null && CARRIER_PACKAGE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) {
            schedule();
        }
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
            if (root.getPackageName() == null || !CARRIER_PACKAGE.contentEquals(root.getPackageName())) {
                trace("活动窗口不属于中国移动");
                return;
            }
            List<String> text = new ArrayList<>();
            collect(root, text, 0);
            CarrierReadings readings = CarrierReadings.parse(text);
            if (readings == null) {
                trace("查询页读数未就绪，节点数=" + text.size()
                        + "，余量页=" + text.contains("余量查询") + "，余额页=" + text.contains("余额查询"));
                return;
            }
            MobileSim.Card sim = MobileSim.match(MobileSim.active(this), readings.account);
            if (sim != null) {
                int seenWindow = readings.trafficValue != null ? root.getWindowId() : -1;
                Snapshot.save(this, sim, readings, seenWindow != trafficWindow);
                trafficWindow = seenWindow;
            } else {
                trace("查询页的账号未匹配本机移动卡");
            }
        } finally {
            recycleLegacyNode(root);
        }
    }

    private static void trace(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message);
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
    }

    @Override
    public void onDestroy() {
        onInterrupt();
        super.onDestroy();
    }
}
