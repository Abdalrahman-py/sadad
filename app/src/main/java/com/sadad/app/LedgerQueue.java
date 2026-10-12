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
    private final EncryptedJournal journal;
    private final InstallationKey installation;
    private final LedgerClient client;
    private final DeviceEnvironment device;
    private final SessionStore session;
    private final String storeId;
    private final String userId;

    private LedgerQueue(Context context, SessionStore session) throws Exception {
        JSONObject account = session.account();
        this.storeId = account.optString("id", "");
        this.session = session;
        String access = session.auth().optString("accessToken", "");
        if (storeId.isEmpty() || access.isEmpty()) throw new IllegalStateException("سجّل الدخول من البوابة أولًا ليُفتح الدفتر المعتمد.");
        this.userId = subjectOf(access);
        this.device = new AndroidEnvironment(context);
        this.installation = new InstallationKey(context);
        this.journal = new EncryptedJournal(context, scopeOf(storeId, userId));
        this.client = new LedgerClient(session, installation);
    }

    static LedgerQueue open(Context context, SessionStore session) throws Exception {
        return new LedgerQueue(context, session);
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
        return LedgerProjection.view(journal.exportPlain(), canAdd(), installation.id(), session.deviceStaffName());
    }

    /** Whether the store may add an entry right now: an unexpired lease on this boot, nothing blocking. */
    boolean canAdd() {
        try { return SignedLedgerSync.canAddOffline(journal, device); }
        catch (Exception unreadable) { return false; }
    }

    /** The enrolment answer: {@code pending} until an admin approves this device. */
    String installationStatus() {
        try { return journal.metadata("installation").optString("status", ""); }
        catch (Exception unreadable) { return ""; }
    }

    JSONObject syncState() {
        try { return journal.metadata("sync"); }
        catch (Exception unreadable) { return new JSONObject(); }
    }

    /**
     * Queues one edit. The command is built from the epoch the last snapshot declared and depends on
     * the operations that introduce the rows it references, so the ledger can accept them in order
     * even when they were typed offline, one after the other.
     *
     * @return the entity id the row will have on the ledger
     */
    String add(String type, String reference, JSONObject payload) throws Exception {
        if (!LedgerCommand.offlineType(type)) throw new IllegalArgumentException("هذا الإجراء يحتاج اتصالًا.");
        JSONObject cache = journal.metadata("cache");
        String epoch = cache.optString("epoch", "");
        if (epoch.isEmpty()) throw new IllegalArgumentException("لم يُجهَّز الدفتر بعد على هذا الجهاز؛ اتصل بالشبكة مرة واحدة قبل الإضافة.");
        JSONObject installationState = journal.metadata("installation");
        if (!"active".equals(installationState.optString("status", ""))) throw new IllegalArgumentException("طلب هذا الجهاز ينتظر موافقة الإدارة.");
        if (!canAdd()) throw new IllegalArgumentException("يلزم اتصال للتحقق قبل إضافة إدخال؛ بقي الطابور والبيانات محفوظين.");
        String entityId = LedgerCommand.newEntityId();
        JSONObject command = LedgerCommand.of(type, entityId, payload, installation.id(),
                installationState.optInt("generation", 0), epoch, dependenciesOf(reference), LedgerCommand.now());
        journal.add(command);
        return entityId;
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

    /** The Auth envelope this device holds, so a caller can see whether the gateway login happened. */
    JSONObject auth() { return session.auth(); }

    void close() {
        try { journal.close(); } catch (Exception ignored) { }
    }

    /** Kept for the JVM harness, which drives the same protocol over the same classes. */
    LedgerApi api() { return client; }

    InstallationSigner signer() { return installation; }

    LedgerJournal journal() { return journal; }
}
