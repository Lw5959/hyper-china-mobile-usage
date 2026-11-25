package local.cmcc.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CarrierReadings {
    private static final String NUMBER = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,12})(?:\\.[0-9]{1,4})?";
    private static final String SIZE = "(" + NUMBER + ")\\s*(TB|GB|MB|KB|B)\\b";
    private static final Pattern REMAIN = Pattern.compile("剩余\\s*:?\\s*" + SIZE, Pattern.CASE_INSENSITIVE);
    private static final Pattern USED = Pattern.compile("已用\\s*:?\\s*" + SIZE, Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE_LABEL = Pattern.compile("^账户可用余额\\s*\\(元\\)");
    private static final Pattern YUAN = Pattern.compile("(?:¥\\s*)?(-?" + NUMBER + ")\\s*元?");
    private static final List<String> UNITS = Arrays.asList("B", "KB", "MB", "GB", "TB");

    final String account;
    final String trafficValue;
    final int trafficUnit;
    final String totalGb;
    final long totalBytes;
    final long usedBytes;
    final Long balanceCents;

    private CarrierReadings(String account, String trafficValue, int trafficUnit, String totalGb,
                            long totalBytes, long usedBytes, Long balanceCents) {
        this.account = account;
        this.trafficValue = trafficValue;
        this.trafficUnit = trafficUnit;
        this.totalGb = totalGb;
        this.totalBytes = totalBytes;
        this.usedBytes = usedBytes;
        this.balanceCents = balanceCents;
    }

    static CarrierReadings parse(List<String> input) {
        List<String> text = new ArrayList<>(input.size());
        for (String item : input) {
            if (item == null) continue;
            String cleaned = Normalizer.normalize(item.replaceAll("[①-⑳]", ""), Normalizer.Form.NFKC).trim();
            if (!cleaned.isEmpty() && cleaned.length() <= 140
                    && (text.isEmpty() || !text.get(text.size() - 1).equals(cleaned))) {
                text.add(cleaned);
            }
        }
        // 首页卡片、充值页和套餐明细有相似数字。只承认两个查询页自己的标签。
        if (text.contains("余量查询")) return trafficFromPage(text);
        if (text.contains("余额查询")) return balanceFromPage(text);
        return null;
    }

    private static CarrierReadings trafficFromPage(List<String> text) {
        for (int index = 0; index < text.size(); index++) {
            String title = text.get(index);
            if (!title.equals("国内通用流量")
                    && !(title.startsWith("国内通用流量") && title.contains("已用"))) continue;
            StringBuilder area = new StringBuilder(title);
            for (int next = index + 1; next < text.size() && next <= index + 8; next++) {
                String part = text.get(next);
                // 下方套餐已经包含在汇总中，定向流量也有自己的汇总。
                if (part.contains("定向流量") || part.contains("流量封顶") || part.contains("通话")
                        || part.startsWith("国内通用流量")) break;
                area.append(' ').append(part);
            }

            Matcher remain = REMAIN.matcher(area);
            Matcher used = USED.matcher(area);
            if (!used.find()) continue;
            Amount usedAmount = amount(used);
            if (usedAmount == null) continue;
            Amount remaining;
            if (remain.find()) {
                remaining = amount(remain);
            } else if (Pattern.compile("剩余\\s*:?\\s*0(?=\\s+已用)").matcher(area).find()) {
                remaining = new Amount(BigDecimal.ZERO, usedAmount.unit);
            } else {
                continue;
            }
            if (remaining == null) continue;
            String total = remaining.gigabytes().add(usedAmount.gigabytes())
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
            long usedBytes = usedAmount.bytes();
            return new CarrierReadings(AccountPhone.inHeader(text, index),
                    remaining.value.toPlainString(), remaining.unit, total, remaining.bytes() + usedBytes,
                    usedBytes, null);
        }
        return null;
    }

    private static CarrierReadings balanceFromPage(List<String> text) {
        for (int index = 0; index < text.size(); index++) {
            String title = text.get(index);
            Matcher label = BALANCE_LABEL.matcher(title);
            if (!label.find()) continue;
            Long cents = cents(title.substring(label.end()).trim());
            if (cents == null) {
                for (int next = index + 1; next < text.size() && next <= index + 3; next++) {
                    String value = text.get(next);
                    if (value.contains("充值") || value.contains("账单") || value.contains("券")) break;
                    cents = cents(value);
                    if (cents != null) break;
                }
            }
            if (cents != null) {
                return new CarrierReadings(AccountPhone.inHeader(text, index), null, -1, null, -1, -1, cents);
            }
        }
        return null;
    }

    private static Long cents(String text) {
        Matcher balance = YUAN.matcher(text);
        if (!balance.matches()) return null;
        try {
            return decimal(balance.group(1)).movePointRight(2).longValueExact();
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static Amount amount(Matcher match) {
        BigDecimal value = decimal(match.group(1));
        int unit = UNITS.indexOf(match.group(2).toUpperCase(Locale.ROOT));
        // 控制中心支持 B、KB、MB、GB 四种单位，TB 换算为 GB。
        if (unit == 4) {
            value = value.multiply(BigDecimal.valueOf(1024));
            unit = 3;
        }
        if (unit < 0 || value.compareTo(BigDecimal.valueOf(1_048_576)) > 0) return null;
        return new Amount(value, unit);
    }

    private static BigDecimal decimal(String number) {
        return new BigDecimal(number.replace(",", ""));
    }

    private static final class Amount {
        final BigDecimal value;
        final int unit;

        Amount(BigDecimal value, int unit) {
            this.value = value;
            this.unit = unit;
        }

        BigDecimal gigabytes() {
            return value.divide(BigDecimal.valueOf(1024).pow(3 - unit));
        }

        long bytes() {
            // 用同一张查询页的原始单位转成系统字节数，避免经过界面上两位小数的流量总额。
            return value.multiply(BigDecimal.valueOf(1024).pow(unit))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }
}
