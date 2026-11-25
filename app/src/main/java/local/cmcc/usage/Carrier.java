package local.cmcc.usage;

enum Carrier {
    MOBILE("中国移动", "com.greenpoint.android.mc10086.activity", "余量查询", "余额查询"),
    TELECOM("中国电信", "com.ct.client", "流量查询", "账户余额"),
    UNICOM("中国联通", "com.sinovatech.unicom.ui", "剩余通用流量", "剩余话费");

    final String label;
    final String packageName;
    final String trafficPage;
    final String balancePage;

    Carrier(String label, String packageName, String trafficPage, String balancePage) {
        this.label = label;
        this.packageName = packageName;
        this.trafficPage = trafficPage;
        this.balancePage = balancePage;
    }

    static Carrier forPackage(CharSequence name) {
        if (name == null) return null;
        for (Carrier carrier : values()) {
            if (carrier.packageName.contentEquals(name)) return carrier;
        }
        return null;
    }

    static Carrier forSim(String mcc, String mnc) {
        if (!"460".equals(mcc) || mnc == null) return null;
        // 部分系统会省略 MNC 前导零，SIM 归属仍使用卡自身的运营商代码。
        String code = mnc.length() == 1 ? "0" + mnc : mnc;
        return switch (code) {
            case "00", "02", "04", "07", "08", "13" -> MOBILE;
            case "01", "06", "09" -> UNICOM;
            case "03", "05", "11", "12" -> TELECOM;
            default -> null;
        };
    }
}
