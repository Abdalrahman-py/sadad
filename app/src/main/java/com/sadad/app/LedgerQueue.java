package com.sadad.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * The signed ledger as the app uses it: one object per store account that owns the encrypted queue,
 * the device key, and the client, and exposes exactly three things to the screens — what the ledger
 * looks like now, how to queue an edit, and one sync pass.
 *
 * <p>The scope is what keeps two stores on one phone apart: it names the project, the store and the
 * signed-in Auth user, and every journal file and key is derived from it. Nothing here reads
 * Android state other than through {@link AndroidEnvironment}, so the protocol above this class is
 * testable without a device.
 */
final class LedgerQueue {
    private final LedgerJournal journal;
    private final InstallationSigner installation;
    private final LedgerApi client;
    private final DeviceEnvironment device;
    private final String operatorName;
    private final String storeId;
    private final String userId;

    static LedgerQueue open(Context context, SessionStore session) throws Exception {
        JSONObject account = session.account();
        String storeId = account.optString("id", "");
        String access = session.auth().optString("accessToken", "");
        if (storeId.isEmpty() || access.isEmpty()) throw new IllegalStateException("سجّل الدخول من البوابة أولًا ليُفتح الدفتر المعتمد.");
        String userId = subjectOf(access);
        if (userId.isEmpty()) throw new IllegalStateException("الجلسة غير صالحة؛ سجّل الدخول من جديد.");
        EncryptedJournal journal = new EncryptedJournal(context, scopeOf(storeId, userId));
        try {
            String legacyId = context.getSharedPreferences("sadid.installation", 0).getString("id", "");
            JSONObject envelope = journal.metadata("lease").optJSONObject("envelope");
            boolean adoptLegacy = false;
            if (!legacyId.isEmpty() && envelope != null && legacyId.equals(new JSONObject(new String(
                    Base64Url.decode(envelope.getString("payload")), StandardCharsets.UTF_8)).optString("installationId"))) {
                SignedLedgerSync.verifyLease(envelope, legacyId, storeId, userId);
                adoptLegacy = true;
            }
            InstallationKey installation = new InstallationKey(context, storeId, adoptLegacy);
            return new LedgerQueue(journal, installation, new LedgerClient(session, installation),
                    new AndroidEnvironment(context), storeId, userId, session.deviceStaffName());
        } catch (Exception error) {
            journal.close();
            throw error;
        }
    }

    LedgerQueue(LedgerJournal journal, InstallationSigner installation, LedgerApi client,
                DeviceEnvironment device, String storeId, String userId, String operatorName) {
        this.journal = journal; this.installation = installation; this.client = client;
        this.device = device; this.storeId = storeId; this.userId = userId; this.operatorName = operatorName;
    }

    /** The journal's file name and key are derived from this; changing it orphans the queue. */
    static String scopeOf(String storeId, String userId) { return LedgerClient.PROJECT + "|" + storeId + "|" + userId; }

    /** The Auth JWT's subject, without which the scope cannot name the signed-in user. */
    static String subjectOf(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return "";
            JSONObject claims = new JSONObject(new String(Base64Url.decode(parts[1]), StandardCharsets.UTF_8));
            return claims.optString("sub", "");
        } catch (Exception malformed) {
            return "";
        }
    }

    /** What the screens read: the server's ledger with this device's queued edits folded in. */
    JSONObject view() throws Exception {
        return LedgerProjection.view(journal.exportPlain(), canAdd(), installation.id(), operatorName);
    }

    /** Whether the store may add an entry right now: an unexpired lease on this boot, nothing blocking. */
    boolean canAdd() {
        try { return SignedLedgerSync.canAddOffline(journal, device); }
        catch (Exception unreadable) { return false; }
    }

    boolean hasPendingWork() throws Exception {
        if (journal.metadata("sync").optBoolean("recoveryRequired")) return true;
        JSONArray entries = journal.entries();
        for (int i = 0; i < entries.length(); i++) {
            if (!java.util.Arrays.asList("confirmed", "rejected", "cancelled", "reviewed")
                    .contains(entries.getJSONObject(i).optString("state"))) return true;
        }
        return false;
    }

    /**
     * Queues one edit. The command is built from the epoch the last snapshot declared and depends on
     * the operations that introduce the rows it references, so the ledger can accept them in order
     * even when they were typed offline, one after the other.
     *
     * @return the entity id the row will have on the ledger
     */
    String add(String type, String reference, JSONObject payload) throws Exception {
        JSONObject command = command(type, reference, payload, LedgerCommand.newEntityId());
        journal.add(command);
        return command.getString("entityId");
    }

    private JSONObject command(String type, String reference, JSONObject payload, String entityId) throws Exception {
        if (!LedgerCommand.offlineType(type)) throw new IllegalArgumentException("هذا الإجراء يحتاج اتصالًا.");
        JSONObject cache = journal.metadata("cache");
        String epoch = cache.optString("epoch", "");
        if (epoch.isEmpty()) throw new IllegalArgumentException("لم يُجهَّز الدفتر بعد على هذا الجهاز؛ اتصل بالشبكة مرة واحدة قبل الإضافة.");
        JSONObject installationState = journal.metadata("installation");
        if (!"active".equals(installationState.optString("status", ""))) throw new IllegalArgumentException("طلب هذا الجهاز ينتظر موافقة الإدارة.");
        if (!canAdd()) throw new IllegalArgumentException("يلزم اتصال للتحقق قبل إضافة إدخال؛ بقي الطابور والبيانات محفوظين.");
        return LedgerCommand.of(type, entityId, payload, installation.id(),
                installationState.optInt("generation", 0), epoch, dependenciesOf(reference), LedgerCommand.now());
    }

    String createContact(JSONObject payload, String initialAmount, String dueDate, String debtNote) throws Exception {
        String amount = initialAmount == null ? "" : initialAmount.trim();
        long cents = amount.isEmpty() ? 0L : SadadDatabase.centsOf(amount);
        if (!amount.isEmpty() && cents <= 0L) throw new IllegalArgumentException("يجب أن يكون مبلغ الدين أكبر من صفر.");
        String contactId = LedgerCommand.newEntityId();
        JSONObject contact = command(LedgerCommand.CONTACT_CREATE, "", payload, contactId);
        JSONArray commands = new JSONArray().put(contact);
        if (cents > 0L) {
            JSONObject debt = command(LedgerCommand.DEBT_CREATE, "",
                    LedgerCommand.debtPayload(contactId, cents, "receivable", debtNote, dueDate), LedgerCommand.newEntityId());
            debt.put("dependsOn", new JSONArray().put(contact.getString("operationId")));
            commands.put(debt);
        }
        journal.addBatch(commands);
        return contactId;
    }

    void payContact(String contactId, long amount, String method, String note) throws Exception {
        if (amount <= 0L) throw new IllegalArgumentException("أدخل مبلغ دفعة أكبر من صفر.");
        JSONObject view = view();
        JSONArray debts = view.optJSONArray("debts");
        java.util.List<JSONObject> open = new java.util.ArrayList<>();
        for (int index = 0; debts != null && index < debts.length(); index++) {
            JSONObject debt = debts.getJSONObject(index);
            if (!"receivable".equals(debt.optString("direction")) || !contactId.equals(debt.optString("contact_id"))) continue;
            if (debt.optLong("remainingCents") <= 0L) continue;
            open.add(debt);
        }
        open.sort(java.util.Comparator.comparingLong(debt -> debt.optLong("createdAtMillis")));
        long remaining = amount;
        JSONArray commands = new JSONArray();
        for (JSONObject debt : open) {
            if (remaining <= 0L) break;
            long allocation = Math.min(debt.optLong("remainingCents"), remaining);
            if (allocation <= 0L) continue;
            String debtId = debt.optString("id");
            commands.put(command(LedgerCommand.PAYMENT_CREATE, debtId,
                    LedgerCommand.paymentPayload(debtId, allocation, method, note), LedgerCommand.newEntityId()));
            remaining -= allocation;
        }
        if (remaining > 0L) throw new IllegalArgumentException("مبلغ الدفعة أكبر من إجمالي الدين الحالي للشخص.");
        journal.addBatch(commands);
    }

    /** The operationIds this edit waits on: the queued operations that introduce the row it names. */
    private JSONArray dependenciesOf(String reference) throws Exception {
        JSONArray dependencies = new JSONArray();
        if (reference == null || reference.isEmpty()) return dependencies;
        JSONArray entries = journal.entries();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            String state = entry.optString("state");
            if ("confirmed".equals(state) || "cancelled".equals(state) || "rejected".equals(state) || "reviewed".equals(state)) continue;
            JSONObject command = entry.optJSONObject("command");
            if (command == null) continue;
            if (reference.equals(command.optString("entityId"))) dependencies.put(command.optString("operationId"));
        }
        return dependencies;
    }

    /** One pass of the signed protocol: enrol, lease, snapshot, push the queue, snapshot again. */
    SignedLedgerSync.Result sync() {
        return SignedLedgerSync.sync(client, journal, installation, device, storeId, userId);
    }

    JSONArray activity() throws Exception { return client.activity(); }

    /**
     * An operation the ledger only accepts while the store is online — an archive or a correction.
     * The snapshot is taken first so the command carries the epoch and the row's current version,
     * and again afterwards so the receipt and the new row are visible at once. A refusal comes back
     * as a thrown message: these are user actions, and the store has to hear the reason.
     */
    JSONObject online(String type, JSONObject payload, long expectedVersion) throws Exception {
        String owner = journal.acquire();
        if (owner == null) throw new IllegalStateException("توجد مزامنة جارية؛ انتظر لحظة ثم أعد الإجراء.");
        try {
            journal.merge(client.snapshot());
            if (journal.metadata("sync").optBoolean("recoveryRequired", false)) {
                throw new IllegalStateException("تغيّر إصدار استرداد الخادم؛ راجع الإيصالات مع الإدارة قبل الإرسال.");
            }
            JSONObject installationState = journal.metadata("installation");
            if (!"active".equals(installationState.optString("status", ""))) throw new IllegalArgumentException("طلب هذا الجهاز ينتظر موافقة الإدارة.");
            String epoch = journal.metadata("cache").optString("epoch", "");
            if (epoch.isEmpty()) throw new IllegalStateException("لم تُقرأ نسخة الدفتر بعد؛ اتصل بالشبكة ثم أعد المحاولة.");
            JSONObject command = LedgerCommand.of(type, LedgerCommand.newEntityId(), payload, installation.id(),
                    installationState.optInt("generation", 0), epoch, new JSONArray(), LedgerCommand.now());
            if (expectedVersion > 0L) command.put("expectedVersion", expectedVersion);
            JSONObject entry = journal.add(command);
            entry = journal.startSend(command.optString("operationId"));
            if (entry == null) throw new IllegalStateException("الإدخال لم يعد قابلًا للإرسال.");
            JSONObject receipt;
            try {
                receipt = client.apply(command, "");
            } catch (SyncFailure refusal) {
                journal.state(entry, refusal.status == 401 || refusal.status == 403 ? "blocked" : "uncertain", refusal.getMessage());
                throw refusal;
            }
            boolean accepted = "accepted".equals(receipt.optString("status"));
            journal.state(entry, accepted ? "acknowledged" : "rejected", receipt.optString("code", ""));
            journal.merge(client.snapshot());
            if (!accepted) throw new IllegalStateException(LedgerMessages.of(receipt.optString("code", "")));
            return receipt;
        } finally {
            journal.release(owner);
        }
    }

    /** The version of an archived contact, as the last snapshot declared it; 0 when unknown. */
    long archivedVersion(String contactId) {
        try {
            return view().optJSONObject("archivedVersions").optLong(contactId, 0L);
        } catch (Exception unreadable) {
            return 0L;
        }
    }

    void close() {
        try { journal.close(); } catch (Exception ignored) { }
    }

    /** Kept for the JVM harness, which drives the same protocol over the same classes. */
    LedgerApi api() { return client; }

    InstallationSigner signer() { return installation; }

    LedgerJournal journal() { return journal; }
}
