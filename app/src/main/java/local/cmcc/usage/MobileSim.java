package local.cmcc.usage;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MobileSim {
    static final class Card {
        final int slot;
        final int subscriptionId;
        final String number;
        final Carrier carrier;

        Card(int slot, int subscriptionId, String number, Carrier carrier) {
            this.slot = slot;
            this.subscriptionId = subscriptionId;
            this.number = number;
            this.carrier = carrier;
        }

        String label() {
            return carrier.label + "　SIM " + (slot + 1) + " · "
                    + (number == null ? "号码不可用" : AccountPhone.masked(number));
        }
    }

    static List<Card> active(Context context) {
        List<Card> cards = new ArrayList<>();
        SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
        TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
        if (manager == null) return cards;
        try {
            List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
            if (subscriptions == null) return cards;
            for (SubscriptionInfo entry : subscriptions) {
                int id = entry.getSubscriptionId();
                int slot = entry.getSimSlotIndex();
                if (slot < 0 || id < 0) continue;
                Carrier carrier = carrierFor(entry, telephony);
                if (carrier != null) cards.add(new Card(slot, id, phoneNumber(manager, entry), carrier));
            }
        } catch (SecurityException | IllegalStateException ignored) {
            // 开机或授权撤销时，等系统订阅列表就绪后再识别卡。
        }
        cards.sort(Comparator.comparingInt(card -> card.slot));
        return cards;
    }

    static Card match(List<Card> cards, Carrier carrier, String account) {
        List<Card> candidates = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (Card card : cards) {
            if (card.carrier != carrier) continue;
            candidates.add(card);
            numbers.add(card.number);
        }
        int index = AccountPhone.matchingIndex(account, numbers);
        return index < 0 ? null : candidates.get(index);
    }

    private static String phoneNumber(SubscriptionManager manager, SubscriptionInfo entry) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return AccountPhone.chinaNumber(manager.getPhoneNumber(entry.getSubscriptionId()));
            }
            return legacyPhoneNumber(entry);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String legacyPhoneNumber(SubscriptionInfo entry) {
        // Android 12 仍通过订阅记录提供号码，Android 13 起使用 SubscriptionManager。
        return AccountPhone.chinaNumber(entry.getNumber());
    }

    private static Carrier carrierFor(SubscriptionInfo entry, TelephonyManager telephony) {
        String mcc = entry.getMccString();
        String mnc = entry.getMncString();
        if ((mcc == null || mcc.isEmpty() || mnc == null || mnc.isEmpty()) && telephony != null) {
            String operator = telephony.createForSubscriptionId(entry.getSubscriptionId()).getSimOperator();
            if (operator != null && operator.length() > 3) {
                mcc = operator.substring(0, 3);
                mnc = operator.substring(3);
            }
        }
        return Carrier.forSim(mcc, mnc);
    }
}
