package local.cmcc.usage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

public final class LocalUsageProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.myUid() && callingUid != 0) {
            throw new SecurityException("系统进程才可读取余量");
        }
        if (!Snapshot.URI.equals(uri) || getContext() == null) {
            throw new IllegalArgumentException("未知的余量查询");
        }
        MatrixCursor result = new MatrixCursor(Snapshot.COLUMNS, 1);
        if (!Snapshot.panelEnabled(getContext())) return result;
        long identity = Binder.clearCallingIdentity();
        try {
            long now = System.currentTimeMillis();
            for (MobileSim.Card sim : MobileSim.active(getContext())) {
                Snapshot value = Snapshot.load(getContext(), sim);
                if (!value.matches(sim)) continue;
                boolean traffic = value.hasTraffic(now);
                boolean balance = value.hasBalance(now);
                if (traffic || balance || value.totalBytes > 0) {
                    result.addRow(new Object[]{sim.slot, traffic ? value.traffic : null,
                            traffic ? value.unit : null, balance ? value.balanceCents : null,
                            sim.subscriptionId, value.trafficTime, value.totalBytes, value.usedBytes});
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.local.cmcc.usage.snapshot";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("余量接口为只读");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("余量接口为只读");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("余量接口为只读");
    }
}
