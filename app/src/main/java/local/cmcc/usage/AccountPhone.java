package local.cmcc.usage;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AccountPhone {
    private static final Pattern NUMBER = Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?<![0-9*])(?:(?:\\+|00)?86[\\s-]*)?(1[3-9](?:[\\s-]*[0-9*]){9})(?![0-9*])");
    private static final Pattern SUFFICIENT = Pattern.compile("[0-9]{3}[0-9*]{4}[0-9]{4}");

    static String chinaNumber(String raw) {
        if (raw == null) return null;
        String number = normalize(raw).replaceAll("[\\s()\\-]", "");
        if (number.startsWith("+86")) {
            number = number.substring(3);
        } else if (number.length() == 15 && number.startsWith("0086")) {
            number = number.substring(4);
        } else if (number.length() == 13 && number.startsWith("86")) {
            number = number.substring(2);
        }
        return NUMBER.matcher(number).matches() ? number : null;
    }

    static String inHeader(List<String> items, int before) {
        String account = null;
        // 只读取汇总区之前的账号，套餐卡片和下方家庭成员可能也包含手机号。
        for (int index = 0; index < before; index++) {
            Matcher matches = ACCOUNT.matcher(normalize(items.get(index)));
            while (matches.find()) {
                String candidate = matches.group(1).replaceAll("[\\s-]", "");
                if (!SUFFICIENT.matcher(candidate).matches()) continue;
                if (account == null) {
                    account = candidate;
                } else {
                    account = combine(account, candidate);
                    if (account == null) return null;
                }
            }
        }
        return account;
    }

    static int matchingIndex(String account, List<String> numbers) {
        if (account == null || !SUFFICIENT.matcher(account).matches()) return -1;
        int winner = -1;
        boolean masked = account.indexOf('*') >= 0;
        for (int index = 0; index < numbers.size(); index++) {
            String number = numbers.get(index);
            if (number == null) {
                // 有另一张无号码的移动卡时，脱敏账号无法排除它。
                if (masked) return -1;
                continue;
            }
            if (combine(account, number) == null) continue;
            if (winner >= 0) return -1;
            winner = index;
        }
        return winner;
    }

    static String masked(String number) {
        return number.substring(0, 3) + "••••" + number.substring(7);
    }

    private static String combine(String a, String b) {
        StringBuilder merged = new StringBuilder(11);
        for (int index = 0; index < 11; index++) {
            char left = a.charAt(index);
            char right = b.charAt(index);
            if (left != right && left != '*' && right != '*') return null;
            merged.append(left == '*' ? right : left);
        }
        return merged.toString();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('×', '*').replace('•', '*').replace('●', '*')
                .replace('·', '*').replace('x', '*').replace('X', '*');
    }
}
