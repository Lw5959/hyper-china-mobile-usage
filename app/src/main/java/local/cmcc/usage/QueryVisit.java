package local.cmcc.usage;

import java.util.Arrays;
import java.util.List;

final class QueryVisit {
    private final String namespace;
    private final List<String> allowedActivities;
    private int homeWindow = -1;
    private int queryWindow = -1;
    private boolean trafficQuery;
    private String account;

    QueryVisit(String namespace, String... allowedActivities) {
        this.namespace = namespace;
        this.allowedActivities = Arrays.asList(allowedActivities);
    }

    void windowChanged(CharSequence className) {
        if (className == null) return;
        String name = className.toString();
        // 系统返回首页时不一定发出 Activity 名称，实际页面由可见字段识别。
        // 账号切换和其它 Activity 会结束本次从首页发起的查询。
        if (name.startsWith(namespace) && name.contains("Activity") && !allowedActivities.contains(name)) {
            clear();
        }
    }

    void seeHomeAccount(int id, String number) {
        homeWindow = id;
        queryWindow = -1;
        account = number;
    }

    String queryAccount(int id, boolean traffic) {
        if (account == null || id == homeWindow) return null;
        // 查询页未提供号码节点时，只沿用首页已确认的账号到下一次查询窗口。
        if (queryWindow < 0) {
            queryWindow = id;
            trafficQuery = traffic;
        }
        return id == queryWindow && traffic == trafficQuery ? account : null;
    }

    void clear() {
        homeWindow = -1;
        queryWindow = -1;
        account = null;
    }
}
