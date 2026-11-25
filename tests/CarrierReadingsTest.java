package local.cmcc.usage;

import java.util.Arrays;

public final class CarrierReadingsTest {
    public static void main(String[] args) {
        CarrierReadings details = parse("余量查询", "138****5678王*", "国内通用流量", "剩余 31.25GB",
                "已用 68.75GB", "定向流量", "剩余 0", "已用 24GB", "国内通用流量 ②", "剩余：20GB/20GB");
        check(details != null && "31.25".equals(details.trafficValue) && "100.00".equals(details.totalGb)
                && details.trafficUnit == 3 && details.balanceCents == null
                && details.totalBytes == 100L * 1024 * 1024 * 1024 && details.usedBytes == 73_819_750_400L
                && "138****5678".equals(details.account), "区分通用、定向与当前账号");
        CarrierReadings empty = parse("余量查询", "国内通用流量", "剩余", "0", "已用 1024MB", "定向流量");
        check(empty != null && "0".equals(empty.trafficValue) && "1.00".equals(empty.totalGb)
                && empty.totalBytes == 1_073_741_824L && empty.usedBytes == empty.totalBytes,
                "零余量与单位换算");
        CarrierReadings mixed = parse("余量查询", "国内通用流量", "剩余 256.5MB", "已用 2GB");
        check(mixed != null && "2.25".equals(mixed.totalGb)
                && mixed.totalBytes == 2_416_443_392L && mixed.usedBytes == 2_147_483_648L,
                "校正字节数沿用页面的原始精度");
        CarrierReadings monthly = parse("余量查询", "国内通用流量", "剩余 75.14GB", "已用 264.86GB");
        check(monthly != null && monthly.totalBytes == 365_072_220_160L
                && monthly.usedBytes == 284_391_259_505L,
                "月套餐总额与已用量来自同一条查询记录");
        CarrierReadings fee = parse("余额查询", "1~5GB", "+86 13987654321", "账户可用余额（元）②", "42.60", "充值");
        check(fee != null && Long.valueOf(4260).equals(fee.balanceCents) && fee.trafficValue == null && "13987654321".equals(fee.account),
                "余额查询页读取账户可用余额");
        CarrierReadings arrears = parse("余额查询", "账户可用余额（元） -3.07");
        check(arrears != null && Long.valueOf(-307).equals(arrears.balanceCents), "负余额含义");
        check(parse("通用流量剩余 31.25GB", "话费余额 42.60元") == null, "忽略首页数值");
        check(parse("余量查询", "定向流量", "剩余 0", "已用 24GB", "国内通用流量", "加载中") == null,
                "忽略定向流量和缺失汇总");
        check("13812345678".equals(AccountPhone.chinaNumber("+86 138-1234-5678")), "统一系统手机号格式");
        check(AccountPhone.matchingIndex("138****5678", Arrays.asList("13987654321", "13812345678")) == 1,
                "双移动卡按脱敏账号识别卡槽");
        check(AccountPhone.matchingIndex("138****5678", Arrays.asList("13812345678", "13898765678")) == -1,
                "两个相同脱敏号码时拒绝同步");
        check(AccountPhone.matchingIndex("138****5678", Arrays.asList("13812345678", null)) == -1,
                "另一卡号码不可用时拒绝不明确的脱敏匹配");
        check(AccountPhone.matchingIndex("13812345678", Arrays.asList("13987654321")) == -1,
                "查询账号与 SIM 不同时拒绝同步");
        check(AccountPhone.inHeader(Arrays.asList("138****5678", "139****4321"), 2) == null,
                "多个冲突账号拒绝同步");
        telecomQueries();
        unicomQueries();
        telecomVisits();
        System.out.println("通过三家运营商查询页、校正字节数和账号匹配测试");
    }

    private static void telecomQueries() {
        check(Carrier.forSim("460", "3") == Carrier.TELECOM && Carrier.forSim("460", "11") == Carrier.TELECOM
                && Carrier.forSim("460", "02") == Carrier.MOBILE, "按 SIM 归属选择运营商");
        check("181****6789".equals(CarrierReadings.homeAccount(Carrier.TELECOM, Arrays.asList(
                "手机", "181****6789福建", "切换号码", "账户余额", "剩余通用流量"))), "电信首页确认号码");
        check("181****6789".equals(CarrierReadings.homeAccount(Carrier.TELECOM, Arrays.asList(
                "181****6789", "切换", "12.34元", "账户余额", "400 MB", "剩余通用流量"))),
                "电信新版首页的切换入口也能确认当前号码");
        CarrierReadings flow = parse(Carrier.TELECOM, "流量查询", "我的套餐", "国内通用流量",
                "本月剩余", "400", "MB", "已用 0KB｜总量 400MB", "主套餐", "剩余200MB/共200MB");
        check(flow != null && "400".equals(flow.trafficValue) && flow.trafficUnit == 2
                && flow.totalBytes == 419_430_400L && flow.usedBytes == 0, "读取电信国内通用流量的汇总");
        CarrierReadings fee = parse(Carrier.TELECOM, "账户余额", "10000", "余额说明", "12.34", "元",
                "去充值", "本月已消费", "8.00元");
        check(fee != null && Long.valueOf(1234).equals(fee.balanceCents), "读取电信查询页余额");
        check(parse(Carrier.TELECOM, "账户余额", "12.34元", "去充值") == null, "忽略电信首页余额");
        check(parse(Carrier.TELECOM, "流量查询", "国内通用流量", "本月剩余 1GB",
                "已用 2GB|总量 1GB") == null, "已有流量超过总量时拒绝校正");
        check(parse(Carrier.TELECOM, "流量查询", "国内通用流量", "本月剩余 400MB",
                "已用 0KB|总量 400MB", "当前查询过于频繁，请稍后再试。") == null,
                "限流页面的旧读数不更新快照");
    }

    private static void unicomQueries() {
        check(Carrier.forSim("460", "01") == Carrier.UNICOM && Carrier.forSim("460", "06") == Carrier.UNICOM
                && Carrier.forSim("461", "01") == null, "联通卡归属和非支持 SIM");
        check("186****1234".equals(CarrierReadings.homeAccount(Carrier.UNICOM, Arrays.asList(
                "首页", "186****1234", "剩余话费", "56.78", "剩余通用流量", "400MB"))),
                "联通首页提供查询页无障碍节点中缺少的号码");
        CarrierReadings flow = parse(Carrier.UNICOM, "186****1234", "查询时间：2026-01-01 10:00:00",
                "流量", "语音", "短信", "通用流量⑦", "剩余：400 MB", "已用：0MB", "定向流量", "剩余：1 GB");
        check(flow != null && "400".equals(flow.trafficValue) && flow.totalBytes == 419_430_400L
                && "186****1234".equals(flow.account), "联通流量按查询页账号保存");
        CarrierReadings fee = parse(Carrier.UNICOM, "剩余话费", "186****1234", "查询时间：2026-01-01 10:00:00",
                "剩余话费", "¥", "56.78", "去交费", "上月结转话费", "65.78");
        check(fee != null && Long.valueOf(5678).equals(fee.balanceCents)
                && "186****1234".equals(fee.account), "联通余额只读去交费上方的汇总");
        check(parse(Carrier.UNICOM, "186****1234", "截至 2026/01/01 10:00", "剩余话费",
                "56.78元", "去交费") == null, "忽略联通首页的读数");
    }

    private static void telecomVisits() {
        QueryVisit visit = new QueryVisit("com.ct.client.", "com.ct.client.activity.MainActivity",
                "com.ct.client.selfservice5.activity.MealAllowanceActivityBlue",
                "com.ct.client.selfservice5.activity.BillBalanceActivity");
        visit.seeHomeAccount(1, "181****6789");
        visit.windowChanged("android.widget.FrameLayout");
        check("181****6789".equals(visit.queryAccount(2, true))
                && visit.queryAccount(2, false) == null && visit.queryAccount(3, true) == null,
                "缺少首页 Activity 事件时仍可按页面确认账号，且只关联已识别的查询窗口");
        visit.seeHomeAccount(1, "181****6789");
        visit.windowChanged("com.ct.client.login.activity.SwitchUserActivity");
        check(visit.queryAccount(4, false) == null, "切换账号时清除旧号码");
    }

    private static CarrierReadings parse(String... nodes) {
        return parse(Carrier.MOBILE, nodes);
    }

    private static CarrierReadings parse(Carrier carrier, String... nodes) {
        return CarrierReadings.parse(carrier, Arrays.asList(nodes));
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
