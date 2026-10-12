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

    static LedgerQueue queue(MemoryJournal journal) throws Exception {
        journal.metadata("cache", new JSONObject().put("epoch", "epoch-1").put("cursor", 1)
                .put("contacts", new JSONArray().put(new JSONObject().put("id", "contact-1").put("name", "عميل")))
                .put("debts", new JSONArray().put(new JSONObject().put("id", "debt-1").put("contact_id", "contact-1")
                        .put("amount_cents", 10000).put("direction", "receivable").put("created_at", "2026-10-10T10:00:00+00:00")))
                .put("payments", new JSONArray()));
        journal.metadata("installation", new JSONObject().put("status", "active").put("generation", 1));
        journal.metadata("lease", new JSONObject().put("boot", 1).put("elapsed", 1000).put("wall", 1000)
                .put("payload", new JSONObject().put("issuedAt", 1000).put("expiresAt", 604801000)));
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
    }
}
