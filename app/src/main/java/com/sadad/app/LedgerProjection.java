package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The ledger as the screens should see it: the server's rows folded together with whatever the queue
 * still holds, so an entry the store typed offline is visible immediately and stays marked until the
 * server has accepted it.
 *
 * <p>Ported from the parked client's {@code LedgerRepository.view()} (tag
 * {@code pre-consolidation-2026-10-11}) and extended with what the native screens need: a
 * {@code createdAtMillis} for every row, and the author's name on rows this device originated, since
 * the signed ledger records the installation and not the person at the counter.
 *
 * <p>Pure JSON, no Android: the whole projection is exercised in the JVM, which is where the money
 * arithmetic belongs.
 */
final class LedgerProjection {
    /** Rows in these states are no longer a pending edit; the server has the last word on them. */
    private static final Set<String> SETTLED = new HashSet<>(java.util.Arrays.asList("confirmed", "cancelled", "rejected", "reviewed"));

    private LedgerProjection() { }

    /** An incomplete response must never become an authoritative empty ledger. */
    static void requireSnapshot(JSONObject snapshot, String storeId) throws JSONException {
        if (snapshot.getInt("schemaVersion") != 1 || !storeId.equals(String.valueOf(snapshot.get("storeId")))
                || snapshot.getString("epoch").isEmpty() || snapshot.getLong("cursor") < 0
                || !"ILS".equals(snapshot.getString("currency"))) {
            throw new JSONException("نطاق نسخة الدفتر أو إصدارها غير صالح؛ بقيت البيانات المحلية محفوظة.");
        }
        for (String table : new String[]{"contacts", "debts", "payments", "adjustments", "reversals", "debtBalances", "outcomes"})
            snapshot.getJSONArray(table);
        Set<String> contacts = new HashSet<>(), debts = new HashSet<>(), payments = new HashSet<>();
        JSONArray rows = snapshot.getJSONArray("contacts");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.getString("id").isEmpty() || !contacts.add(row.getString("id")) || row.getString("name").trim().isEmpty())
                throw new JSONException("نسخة الأشخاص غير صالحة.");
        }
        rows = snapshot.getJSONArray("debts");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.getString("id").isEmpty() || !debts.add(row.getString("id")) || !contacts.contains(row.getString("contact_id"))
                    || !validMoney(row, "amount_cents") || !java.util.Arrays.asList("receivable", "payable").contains(row.getString("direction")))
                throw new JSONException("نسخة الديون غير صالحة.");
        }
        rows = snapshot.getJSONArray("payments");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.getString("id").isEmpty() || !payments.add(row.getString("id")) || !debts.contains(row.getString("debt_id"))
                    || !validMoney(row, "amount_cents")) throw new JSONException("نسخة الدفعات غير صالحة.");
        }
        Set<String> balances = new HashSet<>(), outcomes = new HashSet<>();
        rows = snapshot.getJSONArray("debtBalances");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            long amount = row.getLong("amountCents"), paid = row.getLong("paidCents");
            if (!debts.contains(row.getString("debtId")) || !balances.add(row.getString("debtId")) || !validMoney(row, "amountCents")
                    || paid < 0 || paid > amount || row.getLong("remainingCents") != amount - paid || row.getLong("version") <= 0)
                throw new JSONException("أرصدة نسخة الدفتر غير صالحة.");
        }
        if (!balances.equals(debts)) throw new JSONException("نسخة أرصدة الديون غير مكتملة.");
        Set<String> reversals = new HashSet<>();
        rows = snapshot.getJSONArray("reversals");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!payments.contains(row.getString("payment_id")) || !reversals.add(row.getString("payment_id")))
                throw new JSONException("نسخة إلغاءات الدفعات غير صالحة.");
        }
        rows = snapshot.getJSONArray("adjustments");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!debts.contains(row.getString("debt_id")) || !validMoney(row, "amount_cents") || row.getLong("version") <= 1)
                throw new JSONException("نسخة تصحيحات الديون غير صالحة.");
        }
        rows = snapshot.getJSONArray("outcomes");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.getString("operationId").isEmpty() || !outcomes.add(row.getString("operationId"))
                    || !java.util.Arrays.asList("accepted", "rejected").contains(row.getString("status")))
                throw new JSONException("إيصالات نسخة الدفتر غير صالحة.");
        }
    }

    private static boolean validMoney(JSONObject row, String field) throws JSONException {
        Object value = row.get(field);
        long amount = row.getLong(field);
        return value instanceof Number && ((Number) value).doubleValue() == amount && amount > 0 && amount <= 9007199254740991L;
    }

    /** True when the entry still represents an edit the server has not settled. */
    private static boolean projected(JSONObject entry) {
        return !SETTLED.contains(entry.optString("state"));
    }

    /**
     * @param exported    the journal's plain export: {@code {cache, entries}}
     * @param canAdd      whether this device may add entries right now (lease + not blocked)
     * @param selfId      this installation's id, used to attribute rows to the person at the counter
     * @param staffName   the operator name to attribute this device's rows to
     */
    static JSONObject view(JSONObject exported, boolean canAdd, String selfId, String staffName) throws JSONException {
        JSONObject cache = exported.optJSONObject("cache");
        if (cache == null) cache = new JSONObject();
        JSONArray entries = exported.optJSONArray("entries");
        if (entries == null) entries = new JSONArray();

        JSONArray contacts = copy(cache.optJSONArray("contacts"));
        JSONArray debts = copy(cache.optJSONArray("debts"));
        JSONArray payments = copy(cache.optJSONArray("payments"));
        JSONArray reversals = cache.optJSONArray("reversals");

        Set<String> reversed = new HashSet<>();
        if (reversals != null) {
            for (int index = 0; index < reversals.length(); index++) reversed.add(reversals.getJSONObject(index).optString("payment_id"));
        }

        Map<String, JSONObject> contactById = new LinkedHashMap<>();
        JSONObject archivedVersions = new JSONObject();
        for (int index = 0; index < contacts.length(); index++) {
            JSONObject contact = contacts.getJSONObject(index);
            // An archived contact is gone as far as the store is concerned, but its version is kept:
            // restoring it is a correction, and a correction has to name the version it corrects.
            if (contact.optBoolean("archived")) {
                archivedVersions.put(contact.getString("id"), contact.optLong("version", 1L));
                continue;
            }
            contact.put("createdAtMillis", millisOf(contact.opt("created_at")));
            contact.put("createdBy", "");
            contact.put("pending", false);
            contactById.put(contact.getString("id"), contact);
        }

        Map<String, JSONObject> debtById = new LinkedHashMap<>();
        JSONArray balances = cache.optJSONArray("debtBalances");
        Map<String, JSONObject> balanceByDebt = new LinkedHashMap<>();
        if (balances != null) for (int i = 0; i < balances.length(); i++) {
            JSONObject balance = balances.getJSONObject(i); balanceByDebt.put(balance.getString("debtId"), balance);
        }
        for (int index = 0; index < debts.length(); index++) {
            JSONObject debt = debts.getJSONObject(index);
            // A debt of an archived — or otherwise missing — contact is not part of the store's ledger
            // any more: keeping it would put money in the totals the store cannot see or explain.
            if (!contactById.containsKey(debt.optString("contact_id"))) continue;
            long amount = debt.optLong("amount_cents");
            long paid = 0L;
            long version = 1L;
            if (balances != null) {
                JSONObject row = balanceByDebt.get(debt.optString("id"));
                if (row != null) {
                    amount = row.optLong("amountCents", amount);
                    paid = row.optLong("paidCents"); version = row.optLong("version", 1L);
                }
            } else {
                for (int payment = 0; payment < payments.length(); payment++) {
                    JSONObject row = payments.getJSONObject(payment);
                    if (row.optString("debt_id").equals(debt.optString("id")) && !reversed.contains(row.optString("id"))) {
                        paid += row.optLong("amount_cents");
                    }
                }
            }
            debt.put("currentAmountCents", amount).put("confirmedRemainingCents", amount - paid)
                    .put("remainingCents", amount - paid).put("version", version)
                    .put("createdAtMillis", millisOf(debt.opt("created_at"))).put("createdBy", "").put("pending", false);
            debtById.put(debt.getString("id"), debt);
        }

        for (int index = 0; index < payments.length(); index++) {
            JSONObject payment = payments.getJSONObject(index);
            // A payment is only meaningful under a debt the store can still see.
            if (!debtById.containsKey(payment.optString("debt_id"))) continue;
            payment.put("createdAtMillis", millisOf(payment.opt("created_at")));
            payment.put("createdBy", "");
            payment.put("pending", false);
        }

        // An operation whose dependency was rejected can never be accepted, so it must not be shown
        // as if it were part of the ledger.
        Set<String> excluded = new HashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            String state = entry.optString("state");
            if ("rejected".equals(state) || "cancelled".equals(state) || "reviewed".equals(state)) {
                excluded.add(entry.getJSONObject("command").optString("operationId"));
            }
        }

        Map<String, JSONObject> pendingContacts = new LinkedHashMap<>();
        Map<String, JSONObject> pendingDebts = new LinkedHashMap<>();
        JSONArray pendingPayments = new JSONArray();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            if (!projected(entry)) continue;
            JSONObject command = entry.getJSONObject("command");
            JSONObject payload = command.optJSONObject("payload");
            if (payload == null) continue;
            JSONArray dependencies = command.optJSONArray("dependsOn");
            boolean valid = true;
            if (dependencies != null) {
                for (int dependency = 0; dependency < dependencies.length(); dependency++) {
                    if (excluded.contains(dependencies.optString(dependency))) valid = false;
                }
            }
            if (!valid) {
                entry.put("projectionExcluded", true);
                excluded.add(command.optString("operationId"));
                continue;
            }
            String type = command.optString("type");
            String entityId = command.optString("entityId");
            long at = millisOf(command.opt("createdAt"));
            String author = selfId != null && selfId.equals(command.optString("originInstallationId")) && staffName != null ? staffName : "";
            if (LedgerCommand.CONTACT_CREATE.equals(type)) {
                JSONObject contact = new JSONObject(payload.toString())
                        .put("id", entityId).put("archived", false).put("version", 1).put("pending", true)
                        .put("createdAtMillis", at).put("createdBy", author);
                contactById.put(entityId, contact);
                pendingContacts.put(entityId, contact);
            } else if (LedgerCommand.DEBT_CREATE.equals(type)) {
                String contactId = payload.optString("contactId");
                if (!contactById.containsKey(contactId)) {
                    entry.put("projectionExcluded", true);
                    continue;
                }
                long amount = payload.optLong("amountCents");
                JSONObject debt = new JSONObject(payload.toString())
                        .put("id", entityId).put("contact_id", contactId).put("amount_cents", amount)
                        .put("currentAmountCents", amount).put("confirmedRemainingCents", 0L).put("remainingCents", amount)
                        .put("version", 1).put("pending", true).put("createdAtMillis", at).put("createdBy", author);
                debtById.put(entityId, debt);
                pendingDebts.put(entityId, debt);
            } else if (LedgerCommand.PAYMENT_CREATE.equals(type)) {
                JSONObject debt = debtById.get(payload.optString("debtId"));
                long amount = payload.optLong("amountCents");
                if (debt == null || amount > debt.optLong("remainingCents")) {
                    entry.put("projectionExcluded", true);
                    continue;
                }
                debt.put("remainingCents", debt.optLong("remainingCents") - amount).put("hasPending", true);
                pendingPayments.put(new JSONObject(payload.toString()).put("id", entityId).put("debt_id", payload.optString("debtId"))
                        .put("amount_cents", amount).put("pending", true).put("createdAtMillis", at).put("createdBy", author));
            }
        }

        for (int index = 0; index < payments.length(); index++) {
            JSONObject payment = payments.getJSONObject(index);
            payment.put("reversed", reversed.contains(payment.optString("id")));
        }
        for (int index = 0; index < pendingPayments.length(); index++) payments.put(pendingPayments.getJSONObject(index));

        contacts = new JSONArray();
        BigInteger receivable = BigInteger.ZERO, payable = BigInteger.ZERO, confirmedReceivable = BigInteger.ZERO, confirmedPayable = BigInteger.ZERO;
        for (JSONObject contact : contactById.values()) contacts.put(contact);
        debts = new JSONArray();
        for (JSONObject debt : debtById.values()) {
            debts.put(debt);
            boolean payableDirection = "payable".equals(debt.optString("direction"));
            BigInteger remaining = BigInteger.valueOf(debt.optLong("remainingCents"));
            BigInteger confirmed = BigInteger.valueOf(debt.optLong("confirmedRemainingCents"));
            if (payableDirection) { payable = payable.add(remaining); confirmedPayable = confirmedPayable.add(confirmed); }
            else { receivable = receivable.add(remaining); confirmedReceivable = confirmedReceivable.add(confirmed); }
        }

        return new JSONObject()
                .put("contacts", contacts).put("debts", debts).put("payments", payments).put("entries", entries)
                .put("pendingContacts", pendingContacts.size()).put("pendingDebts", pendingDebts.size()).put("pendingPayments", pendingPayments.length())
                .put("receivableCents", receivable.toString()).put("payableCents", payable.toString())
                .put("confirmedReceivableCents", confirmedReceivable.toString()).put("confirmedPayableCents", confirmedPayable.toString())
                .put("canAdd", canAdd).put("cursor", cache.optLong("cursor")).put("epoch", cache.optString("epoch"))
                .put("archivedVersions", archivedVersions)
                .put("scope", exported.optString("scope"));
    }

    private static JSONArray copy(JSONArray source) throws JSONException {
        return source == null ? new JSONArray() : new JSONArray(source.toString());
    }

    /**
     * A Postgres {@code timestamptz} as milliseconds since the epoch. Written by hand rather than
     * with {@code java.time}, which Android only has from API 26 while this app supports 24, and
     * with a fixed grammar instead of a locale-dependent parser: the ledger always answers
     * {@code YYYY-MM-DDTHH:MM:SS[.fraction]{Z|±HH:MM}}.
     */
    static long millisOf(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return 0L;
        try {
            int year = Integer.parseInt(text.substring(0, 4));
            int month = Integer.parseInt(text.substring(5, 7));
            int day = Integer.parseInt(text.substring(8, 10));
            int hour = Integer.parseInt(text.substring(11, 13));
            int minute = Integer.parseInt(text.substring(14, 16));
            int second = Integer.parseInt(text.substring(17, 19));
            int cut = 19;
            long fraction = 0L;
            if (text.length() > cut && text.charAt(cut) == '.') {
                int end = cut + 1;
                while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
                String digits = text.substring(cut + 1, end);
                if (digits.length() > 3) digits = digits.substring(0, 3);
                while (digits.length() < 3) digits = digits + "0";
                fraction = Long.parseLong(digits);
                cut = end;
            }
            long offsetMinutes = 0L;
            if (cut < text.length()) {
                char sign = text.charAt(cut);
                if (sign == '+' || sign == '-') {
                    int offsetHours = Integer.parseInt(text.substring(cut + 1, cut + 3));
                    int offsetRest = text.length() >= cut + 6 ? Integer.parseInt(text.substring(cut + 4, cut + 6)) : 0;
                    offsetMinutes = offsetHours * 60L + offsetRest;
                    if (sign == '+') offsetMinutes = -offsetMinutes;
                }
            }
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.clear();
            calendar.set(year, month - 1, day, hour, minute, second);
            return calendar.getTimeInMillis() + offsetMinutes * 60_000L + fraction;
        } catch (Exception unparseable) {
            return 0L;
        }
    }
}
