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
        System.out.println("通过 14 组套餐读数、原生校正字节数和手机号匹配测试");
    }

    private static CarrierReadings parse(String... nodes) {
        return CarrierReadings.parse(Arrays.asList(nodes));
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
