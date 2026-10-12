package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * The one place a signed-ledger command is built, so its shape is stated once and tested without a
 * device: a v3 command carries the operation id and the entity id it introduces, the installation
 * that made it, the epoch the client believes the ledger is on, and the operations it depends on.
 *
 * <p>{@code createdAt} is formatted exactly as {@code timestamptz} accepts, because the ledger
 * parses it back and rejects anything else.
 */
final class LedgerCommand {
    /** The offline types this device may queue; everything else needs the server's answer first. */
    static final String CONTACT_CREATE = "contact.create";
    static final String DEBT_CREATE = "debt.create";
    static final String PAYMENT_CREATE = "payment.create";

    private LedgerCommand() { }

    static boolean offlineType(String type) {
        return CONTACT_CREATE.equals(type) || DEBT_CREATE.equals(type) || PAYMENT_CREATE.equals(type);
    }

    /** A fresh entity id: the ledger keys its rows on it, so the client mints it before the queue. */
    static String newEntityId() { return UUID.randomUUID().toString(); }

    static JSONObject of(String type, String entityId, JSONObject payload, String installationId, int generation,
                         String epoch, JSONArray dependsOn, String createdAt) throws JSONException {
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("operationId", UUID.randomUUID().toString())
                .put("entityId", entityId)
                .put("type", type)
                .put("originInstallationId", installationId)
                .put("originGeneration", generation)
                .put("createdAt", createdAt)
                .put("dependsOn", dependsOn == null ? new JSONArray() : dependsOn)
                .put("expectedEpoch", epoch == null ? "" : epoch)
                .put("payload", payload);
    }

    /** ISO-8601 with milliseconds and an explicit offset, the only shape the ledger's parser takes. */
    static String now() { return now(System.currentTimeMillis()); }

    static String now(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    /**
     * The contact payload. The ledger strips spaces and brackets from the phone, accepts only a full
     * international number, and refuses a number already on another contact of the same store — so
     * the client sends what it has and lets the ledger be the judge: a refusal is an answer too.
     */
    static JSONObject contactPayload(String name, String phone, String category, String note) throws JSONException {
        return new JSONObject()
                .put("name", name == null ? "" : name.trim())
                .put("phone", phone == null ? "" : phone.trim())
                .put("category", category == null ? "" : category.trim())
                .put("note", note == null ? "" : note.trim());
    }

    static JSONObject debtPayload(String contactId, long amountCents, String direction, String note, String dueDate) throws JSONException {
        JSONObject payload = new JSONObject()
                .put("contactId", contactId)
                .put("amountCents", amountCents)
                .put("direction", direction == null || direction.isEmpty() ? "receivable" : direction)
                .put("note", note == null ? "" : note.trim());
        String due = dueDate == null ? "" : dueDate.trim();
        if (!due.isEmpty()) payload.put("dueDate", due);
        return payload;
    }

    static JSONObject paymentPayload(String debtId, long amountCents, String method, String note) throws JSONException {
        return new JSONObject()
                .put("debtId", debtId)
                .put("amountCents", amountCents)
                .put("method", method(method))
                .put("note", note == null ? "" : note.trim());
    }

    /** The ledger's method vocabulary. An unknown value becomes cash rather than an invented method. */
    static String method(String value) {
        String method = value == null ? "" : value.trim();
        if ("bank".equals(method) || "wallet".equals(method) || "wallet_jawwal".equals(method) || "wallet_palpay".equals(method)) return method;
        return "cash";
    }

    /** The archive reason the correction types require; never empty, so a rejection is explainable. */
    static String reason(String value) {
        String reason = value == null ? "" : value.trim();
        return reason.length() > 2000 ? reason.substring(0, 2000) : reason;
    }
}
