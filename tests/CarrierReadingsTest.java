package local.cmcc.usage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        CarrierReadings splitAccountFee = parse("余额查询", "已订业务", "138****5678", "账户可用余额", "（元）",
                "?", "12.34", "本月已消费（元）", "56.78");
        check(splitAccountFee != null && Long.valueOf(1234).equals(splitAccountFee.balanceCents)
                && "138****5678".equals(splitAccountFee.account), "余额标题和单位分节点时按查询号码读取");
        CarrierReadings mergedAccountFee = parse("余额查询", "138****5678",
                "账户可用余额（元） ? 12.34 本月已消费（元）: 56.78");
        check(mergedAccountFee != null && Long.valueOf(1234).equals(mergedAccountFee.balanceCents),
                "余额和本月消费合并在一个节点时读取余额");
        check(parse("余额查询", "账户可用余额（元）", "本月已消费（元）", "56.78") == null,
                "可用余额缺失时不读取本月消费");
        CarrierReadings generalFee = parse("余额查询", "充值缴费", "账单查询", "138****5678", "合户", "个人",
                "11月", "2026", "通用余额（元）", "?", "12.34", "充值", "其他余额（元）", "56.78",
                "信控额度", "0.00/0.00", "通用账本", "通用余额", "23.45", "上期结余", "67.89");
        check(generalFee != null && Long.valueOf(1234).equals(generalFee.balanceCents)
                && "138****5678".equals(generalFee.account) && generalFee.trafficValue == null,
                "移动余额新布局读取顶部通用余额及对应号码");
        CarrierReadings combinedFee = parse("余额查询", "通用余额(元) 余额说明 -12.34 充值› 其他余额(元) 56.78");
        check(combinedFee != null && Long.valueOf(-1234).equals(combinedFee.balanceCents),
                "合并节点中的充值按钮和其他余额不影响通用余额");
        CarrierReadings splitFee = parse("余额查询", "通用余额", "（元）", "说明", "0.00");
        check(splitFee != null && Long.valueOf(0).equals(splitFee.balanceCents), "识别分开显示的单位与零余额");
        check(parse("余额查询", "通用余额(元)", "?", "其他余额(元)", "56.78", "信控额度", "34.56") == null,
                "通用余额缺失时不使用其他余额或信控额度");
        check(parse("余额查询", "通用账本", "通用余额(元)", "23.45", "上期结余", "67.89") == null
                && parse("余额查询", "通用余额(元) 23.45 = 67.89 - 44.44") == null
                && parse("余额查询", "通用余额", "23.45") == null,
                "账本金额和计算式不能用作当前余额");
        check(parse("通用流量剩余 31.25GB", "话费余额 42.60元") == null, "忽略首页数值");
        check(parse("余量查询", "定向流量", "剩余 0", "已用 24GB", "国内通用流量", "加载中") == null,
                "忽略定向流量和缺失汇总");
        mobileCombinedPages();
        mobileSharedTraffic();
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

    private static void mobileCombinedPages() {
        CarrierReadings fee = parse("返回 关闭 余额查询\n已订业务 138****5678 用户 "
                + "账户可用余额（元） ? 12.34 本月已消费（元） 98.76 温馨提示 " + "余额查询说明 ".repeat(32));
        check(fee != null && Long.valueOf(1234).equals(fee.balanceCents) && "138****5678".equals(fee.account),
                "移动页面合并为长描述时识别查询标题、号码和可用余额");
        CarrierReadings flow = parse("返回 余量查询, 标题", "138****5678 用户 国内通用流量 "
                + "剩余 31.25GB 已用 68.75GB 定向流量 剩余 90GB 已用 10GB");
        check(flow != null && "31.25".equals(flow.trafficValue) && flow.totalBytes == 100L * 1024 * 1024 * 1024
                && "138****5678".equals(flow.account), "合并节点中的通用流量按页面号码保存");
        check(parse("请打开余额查询 138****5678 账户可用余额（元） 12.34") == null
                && parse("返回 余额查询 账户可用余额（元） 本月已消费（元） 98.76") == null
                && parse("返回 余量查询 国内通用流量 加载中 定向流量 剩余 90GB 已用 10GB") == null,
                "合并节点缺少查询标题或汇总金额时不使用其它数据");
        String evidence = CarrierReadings.mobileEvidence(Arrays.asList(
                "返回 余额查询", "138****5678", "账户可用余额（元） 12.34 本月已消费（元） 98.76"));
        check(evidence.contains("文本节点 3") && evidence.contains("余额查询、账户可用余额、本月已消费")
                && !evidence.contains("138****5678") && !evidence.contains("12.34") && !evidence.contains("98.76"),
                "窗口诊断只记录固定字段与计数");
        check("余额查询".equals(CarrierReadings.mobileQueryTitle(Arrays.asList("返回", "余额查询", "关闭")))
                && CarrierReadings.mobileQueryTitle(Arrays.asList("返回", "余量查询", "余额查询")) == null
                && CarrierReadings.mobileQueryTitle(Arrays.asList("打开余额查询获取详单")) == null,
                "只有明确且单一的查询标题才尝试恢复正文");
    }

    private static void mobileSharedTraffic() {
        String[] details = {"国内通用流量 ?", "流量封顶服务", "全家享130G通用流量包", "可共享",
                "已用: 55.86GB", "剩余: 74.14GB/130GB", "激活时间"};
        CarrierReadings summary = CarrierReadings.parse(Carrier.MOBILE, withDetails(details,
                "余量查询", "138****5678", "流量", "通话", "短彩信", "其他", "国内通用流量",
                "剩余", "174.14", "GB", "已用", "75.86", "GB",
                "共享流量", "剩余 174.14GB", "我已使用 26.64GB"));
        check(summary != null && "174.14".equals(summary.trafficValue) && "250.00".equals(summary.totalGb)
                && summary.totalBytes == 250L * 1024 * 1024 * 1024 && summary.usedBytes == 81_454_054_769L
                && "138****5678".equals(summary.account), "共享套餐页面读取顶部 250 GB 汇总");
        check(CarrierReadings.parse(Carrier.MOBILE, withDetails(details, "余量查询", "138****5678")) == null,
                "顶部卡片缺失时拒绝使用单个套餐");
        check(parse("余量查询", "138****5678", "国内通用流量", "剩余 174.14GB",
                "共享流量 剩余 174.14GB 我已使用 26.64GB",
                "国内通用流量", "剩余 74.14GB", "已用 55.86GB") == null,
                "汇总缺少已用字段时不能借用共享行或套餐明细");
        check(parse("余量查询", "138****5678", "国内通用流量", "已用 55.86GB", "剩余 74.14GB / 130GB") == null
                && parse("余量查询", "138****5678", "国内通用流量 剩余 74.14GB/130GB 已用 55.86GB") == null,
                "拒绝套餐明细的剩余斜杠总量");
        CarrierReadings compact = parse("余额查询入口", "余量查询", "138****5678",
                "国内通用流量剩余174.14GB已用75.86GB共享流量剩余174.14GB我已使用26.64GB");
        check(compact != null && "250.00".equals(compact.totalGb), "紧凑的合并节点只读取顶部汇总");
        CarrierReadings reversed = parse("余量查询", "138****5678", "国内通用流量", "已用 1GB", "剩余 2GB");
        check(reversed != null && "3.00".equals(reversed.totalGb), "保留字段顺序不同的完整汇总");
    }

    private static List<String> withDetails(String[] details, String... fields) {
        List<String> text = new ArrayList<>(Arrays.asList(fields));
        text.addAll(Arrays.asList(details));
        return text;
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
