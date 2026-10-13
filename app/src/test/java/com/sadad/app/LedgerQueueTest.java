package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class LedgerQueueTest {
    @Test public void anOverpaymentLeavesTheLedgerUnchanged() throws Exception {
        LedgerQueue queue = queue(new MemoryJournal());
        assertThrows(IllegalArgumentException.class, () -> queue.payContact("contact-1", 20000, "cash", ""));
        assertEquals("10000", queue.view().getString("receivableCents"));
        assertEquals(0, queue.view().getJSONArray("payments").length());
    }

    @Test public void anInvalidFirstDebtDoesNotCreateTheContact() throws Exception {
        LedgerQueue queue = queue(new MemoryJournal());
        assertThrows(IllegalArgumentException.class, () -> queue.createContact(
                LedgerCommand.contactPayload("جديد", "", "عميل", ""), "invalid", "", ""));
        assertEquals(1, queue.view().getJSONArray("contacts").length());
    }

    @Test public void aPaymentSpansDebtsWithoutChangingItsTotal() throws Exception {
        MemoryJournal journal = new MemoryJournal();
        LedgerQueue queue = queue(journal);
        journal.metadata("cache").getJSONArray("debts").put(new JSONObject()
                .put("id", "debt-2").put("contact_id", "contact-1").put("amount_cents", 5000)
                .put("direction", "receivable").put("created_at", "2026-10-11T10:00:00+00:00"));
        queue.payContact("contact-1", 12000, "cash", "دفعة واحدة");
        assertEquals("3000", queue.view().getString("receivableCents"));
        JSONArray payments = queue.view().getJSONArray("payments");
        assertEquals(2, payments.length());
        assertEquals(10000, payments.getJSONObject(0).getLong("amountCents"));
        assertEquals(2000, payments.getJSONObject(1).getLong("amountCents"));
    }

    @Test public void aNonPositiveFirstDebtDoesNotCreateTheContact() throws Exception {
        LedgerQueue queue = queue(new MemoryJournal());
        assertThrows(IllegalArgumentException.class, () -> queue.createContact(
                LedgerCommand.contactPayload("جديد", "", "عميل", ""), "0", "", ""));
        assertEquals(1, queue.view().getJSONArray("contacts").length());
    }

    @Test public void switchingIsBlockedUntilEveryWriteIsSettled() throws Exception {
        MemoryJournal journal = new MemoryJournal();
        LedgerQueue queue = queue(journal);
        assertFalse(queue.hasPendingWork());
        queue.payContact("contact-1", 1000, "cash", "");
        assertTrue(queue.hasPendingWork());
        JSONObject entry = journal.entries.getJSONObject(0);
        journal.state(entry, "acknowledged", "");
        assertTrue(queue.hasPendingWork());
        journal.state(entry, "confirmed", "");
        assertFalse(queue.hasPendingWork());
        journal.metadata("sync", new JSONObject().put("recoveryRequired", true));
        assertTrue(queue.hasPendingWork());
    }

    @Test public void aRecoveryDecisionPreventsNewOfflineWrites() throws Exception {
        MemoryJournal journal = new MemoryJournal();
        LedgerQueue queue = queue(journal);
        journal.metadata("sync", new JSONObject().put("recoveryRequired", true));
        assertFalse(queue.canAdd());
        assertThrows(IllegalArgumentException.class, () -> queue.payContact("contact-1", 1000, "cash", ""));
        assertEquals("10000", queue.view().getString("receivableCents"));
    }

    @Test public void anOldInstallationsLeaseCannotAuthorizeNewWrites() throws Exception {
        MemoryJournal journal = new MemoryJournal();
        LedgerQueue queue = queue(journal);
        journal.metadata("lease").getJSONObject("payload").put("installationId", "old-installation");
        assertFalse(queue.canAdd());
        assertThrows(IllegalArgumentException.class, () -> queue.payContact("contact-1", 1000, "cash", ""));
        assertEquals(0, queue.view().getJSONArray("payments").length());
    }

    @Test public void anUninitializedJournalCannotReplaceTheSavedLocalView() throws Exception {
        MemoryJournal journal = new MemoryJournal(); LedgerQueue queue = queue(journal);
        assertTrue(queue.hasSnapshot());
        journal.metadata("cache", new JSONObject()); assertFalse(queue.hasSnapshot());
        journal.metadata("cache", new JSONObject().put("epoch", "broken"));
        assertThrows(org.json.JSONException.class, queue::hasSnapshot);
    }

    @Test public void manifestsKeepOriginalCommandsAndExcludeSettledEntries() throws Exception {
        MemoryJournal journal = new MemoryJournal(); LedgerQueue queue = queue(journal);
        queue.payContact("contact-1", 1000, "cash", "");
        String wire = journal.entries.getJSONObject(0).getString("wire");
        journal.entries.getJSONObject(0).put("state", "blocked");
        assertEquals("installation-1", queue.recoveryOrigins().getString(0));
        assertEquals(wire, new JSONArray(new String(queue.recoveryManifest("installation-1"), java.nio.charset.StandardCharsets.UTF_8)).getJSONObject(0).toString());
        journal.entries.getJSONObject(0).put("state", "confirmed");
        assertEquals(0, queue.recoveryOrigins().length());
        assertThrows(IllegalArgumentException.class, () -> queue.recoveryManifest("installation-1"));
    }

    @Test public void activePhonesCannotRotateAndRecoveryPermitsAreValidated() throws Exception {
        MemoryJournal journal = new MemoryJournal(); LedgerQueue queue = queue(journal);
        assertThrows(IllegalStateException.class, queue::prepareRegistration);
        assertTrue(queue.canAdd());
        assertThrows(IllegalArgumentException.class, () -> queue.recoveryPermit("invalid"));
        assertEquals(0, journal.metadata("recovery").length());
        String id = "a0000000-0000-4000-8000-000000000001";
        queue.recoveryPermit(id); assertEquals(id, journal.metadata("recovery").getString("permitId"));
        journal.metadata("sync", new JSONObject().put("blockedCode", "installation_revoked"));
        queue.prepareRegistration(); assertFalse(queue.canAdd()); assertEquals(1, queue.view().getJSONArray("contacts").length());
    }

    static LedgerQueue queue(MemoryJournal journal) throws Exception {
        journal.metadata("cache", new JSONObject().put("schemaVersion",1).put("storeId",7).put("currency","ILS").put("epoch", "epoch-1").put("cursor", 1)
                .put("contacts", new JSONArray().put(new JSONObject().put("id", "contact-1").put("name", "عميل")))
                .put("debts", new JSONArray().put(new JSONObject().put("id", "debt-1").put("contact_id", "contact-1")
                        .put("amount_cents", 10000).put("direction", "receivable").put("created_at", "2026-10-10T10:00:00+00:00")))
                .put("payments", new JSONArray()).put("adjustments",new JSONArray()).put("reversals",new JSONArray()).put("outcomes",new JSONArray())
                .put("debtBalances",new JSONArray().put(new JSONObject().put("debtId","debt-1").put("amountCents",10000).put("paidCents",0).put("remainingCents",10000).put("version",1))));
        journal.metadata("installation", new JSONObject().put("status", "active").put("generation", 1));
        journal.metadata("lease", new JSONObject().put("boot", 1).put("elapsed", 1000).put("wall", 1000)
                .put("payload", new JSONObject().put("projectRef", LedgerClient.PROJECT).put("installationId", "installation-1")
                        .put("storeId", 7).put("userId", "user-1").put("generation", 1).put("issuedAt", 1000).put("expiresAt", 604801000)));
        InstallationSigner signer = new InstallationSigner() {
            public String id() { return "installation-1"; }
            public JSONObject publicKey() { return new JSONObject(); }
            public String sign(String method, String route, String raw, String time, String nonce) { throw new AssertionError("Offline edit must not sign HTTP"); }
        };
        DeviceEnvironment clock = new DeviceEnvironment() {
            public long bootCount() { return 1; }
            public long elapsedRealtime() { return 1000; }
            public long wallClock() { return 1000; }
        };
        return new LedgerQueue(journal, signer, null, clock, "7", "user-1", "موظف");
    }

    // The database seam: the projection and queue logic remain the production implementations.
    static final class MemoryJournal implements LedgerJournal {
        final Map<String, JSONObject> metadata = new HashMap<>();
        final JSONArray entries = new JSONArray();
        public JSONObject metadata(String key) { return metadata.getOrDefault(key, new JSONObject()); }
        public void metadata(String key, JSONObject value) { metadata.put(key, value); }
        public JSONArray entries() { return entries; }
        public JSONObject add(JSONObject command) throws Exception {
            JSONObject entry = new JSONObject().put("command", command).put("wire", command.toString()).put("state", "queued");
            entries.put(entry); return entry;
        }
        public JSONObject exportPlain() throws Exception { return new JSONObject().put("cache", metadata("cache")).put("entries", entries); }
        public void addBatch(JSONArray commands) throws Exception { for (int i=0;i<commands.length();i++) add(commands.getJSONObject(i)); }
        public JSONObject startSend(String id) { throw new AssertionError("Unexpected online send"); }
        public void state(JSONObject entry, String state, String error) throws Exception { entry.put("state", state); }
        public void merge(JSONObject snapshot) { metadata.put("cache", snapshot); }
        public String acquire() { return "owner"; }
        public void renew(String owner) { }
        public void release(String owner) { }
        public void close() { }
        public void importPlain(JSONObject bundle) { throw new AssertionError("Unexpected recovery import"); }
        public void acceptEpoch(JSONObject snapshot) { throw new AssertionError("Unexpected epoch reconciliation"); }
    }
}
