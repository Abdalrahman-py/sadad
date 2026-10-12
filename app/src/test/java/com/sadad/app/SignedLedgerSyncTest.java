package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The signed-ledger pass as behaviour: a journal, a device clock and the five ledger exchanges, all
 * faked, so what a pass does on approval, on a refusal, on a dependency in flight and on a stale
 * epoch is pinned down in the JVM rather than discovered on a phone.
 */
public final class SignedLedgerSyncTest {

    /** org.json reports failures as checked exceptions; in a fixture that is always an assertion. */
    static JSONObject json(String raw) {
        try { return new JSONObject(raw); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
    }

    private static final String INSTALLATION = "a2bba31e-dc52-486f-af34-891d5364ca9f";
    private static final String STORE = "7";
    private static final String USER = "9f0c1d2e-3333-4444-5555-666677778888";

    // ------------------------------------------------------------------ fakes

    private static final class FakeJournal implements LedgerJournal {
        public JSONObject exportPlain() throws Exception { return new JSONObject().put("cache", metadata("cache")).put("entries", entries()); }
        public void close() { }
        public void addBatch(JSONArray commands) throws Exception { for (int i=0;i<commands.length();i++) add(commands.getJSONObject(i)); }
        final Map<String, JSONObject> metadata = new HashMap<>();
        final List<JSONObject> entries = new ArrayList<>();
        boolean locked;
        int merges;

        public JSONObject metadata(String name) { return metadata.getOrDefault(name, new JSONObject()); }
        public void metadata(String name, JSONObject value) { metadata.put(name, value); }
        public JSONArray entries() { JSONArray list = new JSONArray(); for (JSONObject entry : entries) list.put(entry); return list; }
        public JSONObject add(JSONObject command) throws Exception { JSONObject entry = entry(command); entries.add(entry); return entry; }
        public JSONObject startSend(String operationId) throws Exception {
            for (JSONObject entry : entries) {
                if (!operationId.equals(entry.getJSONObject("command").optString("operationId"))) continue;
                if (!"queued".equals(entry.optString("state")) && !"uncertain".equals(entry.optString("state"))) return null;
                entry.put("state", "sending");
                return entry;
            }
            return null;
        }
        public void state(JSONObject entry, String state, String error) throws Exception { entry.put("state", state).put("error", error == null ? "" : error); }
        public void merge(JSONObject snapshot) { merges++; metadata.put("cache", snapshot); }
        public String acquire() { if (locked) return null; locked = true; return "owner-1"; }
        public void renew(String owner) { }
        public void release(String owner) { locked = false; }

        static JSONObject entry(JSONObject command) throws Exception {
            return new JSONObject().put("command", command).put("wire", command.toString()).put("state", "queued");
        }
    }

    private static final class FakeLedger implements LedgerApi {
        JSONObject enrolAnswer = json("{\"status\":\"active\",\"installationId\":\"" + INSTALLATION + "\",\"generation\":1}");
        JSONObject leaseAnswer;
        SyncFailure enrollmentFailure;
        JSONObject snapshotAnswer = json("{\"epoch\":\"epoch-1\",\"cursor\":1}");
        final Map<String, JSONObject> receipts = new HashMap<>();
        final Map<String, SyncFailure> refusals = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        public JSONObject enroll(JSONObject publicKey) throws Exception { calls.add("enroll"); if (enrollmentFailure != null) throw enrollmentFailure; return enrolAnswer; }
        public JSONObject snapshot() { calls.add("snapshot"); return snapshotAnswer; }
        public JSONObject lease() { calls.add("lease"); return leaseAnswer; }
        public JSONObject apply(JSONObject command, String permit) throws Exception {
            calls.add("apply:" + command.optString("operationId"));
            String id = command.optString("operationId");
            SyncFailure refusal = refusals.get(id);
            if (refusal != null) throw refusal;
            JSONObject receipt = receipts.get(id);
            if (receipt == null) throw new AssertionError("unscripted operation " + id);
            return receipt;
        }
        public JSONArray outcomes(JSONArray operationIds) { return new JSONArray(); }
        public JSONArray activity() { throw new AssertionError("Sync must not fetch activity"); }
    }

    private static final class FakeDevice implements DeviceEnvironment {
        long boot = 7, elapsed = 1000, wall = 1_700_000_000_000L;
        public long bootCount() { return boot; }
        public long elapsedRealtime() { return elapsed; }
        public long wallClock() { return wall; }
    }

    /** Signs a lease the way sadid-ledger does: ECDSA over the payload bytes, sent as 64 raw bytes. */
    private static JSONObject lease(String installation, long storeId, String userId, long issuedAt, long expiresAt, KeyPair signer)
            throws Exception {
        String payload = new JSONObject().put("schemaVersion", 1).put("projectRef", LedgerClient.PROJECT)
                .put("storeId", storeId).put("userId", userId).put("installationId", installation)
                .put("generation", 1).put("issuedAt", issuedAt).put("expiresAt", expiresAt)
                .put("allowed", new JSONArray().put("contact.create")).toString();
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(signer.getPrivate());
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        ECPublicKey publicKey = (ECPublicKey) signer.getPublic();
        return new JSONObject()
                .put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)))
                .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(raw(signature.sign())))
                .put("publicKey", new JSONObject().put("kty", "EC").put("crv", "P-256")
                        .put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(thirtyTwo(publicKey.getW().getAffineX().toByteArray())))
                        .put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(thirtyTwo(publicKey.getW().getAffineY().toByteArray()))));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] thirtyTwo(byte[] value) {
        byte[] out = new byte[32];
        int size = Math.min(value.length, 32);
        System.arraycopy(value, value.length - size, out, 32 - size, size);
        return out;
    }

    private static byte[] raw(byte[] der) {
        int i = 2;
        if ((der[1] & 0xff) > 127) i = 2 + (der[1] & 0x7f);
        int rLength = der[i + 1] & 0xff, rStart = i + 2;
        int sIndex = rStart + rLength, sLength = der[sIndex + 1] & 0xff, sStart = sIndex + 2;
        byte[] out = new byte[64];
        place(der, rStart, rLength, out, 0);
        place(der, sStart, sLength, out, 32);
        return out;
    }

    private static void place(byte[] der, int start, int length, byte[] out, int offset) {
        int skip = 0;
        while (length - skip > 32) skip++;
        int copy = length - skip;
        System.arraycopy(der, start + skip, out, offset + (32 - copy), copy);
    }

    private static final class FakeSigner implements InstallationSigner {
        public String id() { return INSTALLATION; }
        public JSONObject publicKey() { return json("{\"kty\":\"EC\"}"); }
        public String sign(String method, String route, String raw, String time, String nonce) { return "signature"; }
    }

    private static JSONObject command(String operationId, String epoch, String origin) throws Exception {
        return new JSONObject().put("operationId", operationId).put("entityId", UUID.randomUUID().toString())
                .put("type", "contact.create").put("originInstallationId", origin).put("expectedEpoch", epoch);
    }

    private static SignedLedgerSync.Result run(FakeLedger ledger, FakeJournal journal, FakeDevice device) {
        return SignedLedgerSync.sync(ledger, journal, new FakeSigner(), device, STORE, USER);
    }

    // ------------------------------------------------------------------ the pass

    @Test public void anApprovedFirstSyncEnablesAnOfflineContact() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.enrolAnswer = new JSONObject().put("status", "active").put("installationId", INSTALLATION).put("generation", 1);
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        assertEquals(SignedLedgerSync.Outcome.SYNCED, run(ledger, journal, device).outcome);
        LedgerQueue queue = new LedgerQueue(journal, new FakeSigner(), ledger, device, STORE, USER, "موظف");
        queue.add(LedgerCommand.CONTACT_CREATE, "", LedgerCommand.contactPayload("عميل", "", "عميل", ""));
        assertEquals(1, queue.view().getJSONArray("contacts").length());
    }

    @Test public void anUnapprovedDeviceIsReportedAsWaitingWithNothingElseAttempted() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.enrolAnswer = new JSONObject().put("status", "pending").put("installationId", INSTALLATION).put("generation", 1);
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();

        SignedLedgerSync.Result result = run(ledger, journal, device);

        assertEquals(SignedLedgerSync.Outcome.WAITING_APPROVAL, result.outcome);
        assertEquals("no lease and no snapshot are fetched before approval", List.of("enroll"), ledger.calls);
        assertFalse("the lock must be released", journal.locked);
    }

    @Test public void pendingApprovalDisablesAnOldOfflineLease() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        run(ledger, journal, device);
        ledger.enrolAnswer.put("status", "pending");
        assertEquals(SignedLedgerSync.Outcome.WAITING_APPROVAL, run(ledger, journal, device).outcome);
        assertFalse(new LedgerQueue(journal, new FakeSigner(), ledger, device, STORE, USER, "موظف").canAdd());
    }

    @Test public void aFailedRetryKeepsTheRecoveryDecisionBlocked() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        run(ledger, journal, device);
        journal.metadata("sync", new JSONObject().put("recoveryRequired", true));
        ledger.leaseAnswer = null;
        assertEquals(SignedLedgerSync.Outcome.RETRY_LATER, run(ledger, journal, device).outcome);
        assertFalse(new LedgerQueue(journal, new FakeSigner(), ledger, device, STORE, USER, "موظف").canAdd());
    }

    @Test public void anOldSessionBindingRequestsSignInRatherThanAnEndlessRetry() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.enrollmentFailure = new SyncFailure(409, new JSONObject(), "الجلسة مرتبطة بتركيب مختلف.", "session_installation_conflict");
        FakeJournal journal = new FakeJournal();
        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());
        assertEquals(SignedLedgerSync.Outcome.SIGN_IN_REQUIRED, result.outcome);
        assertFalse(result.retryable());
        assertFalse(journal.locked);
    }

    @Test public void aPassRenewsTheLeaseAndKeepsItWhereTheOfflineRulesRead() throws Exception {
        FakeLedger ledger = new FakeLedger();
        KeyPair signer = keyPair();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, signer);
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();

        SignedLedgerSync.Result result = run(ledger, journal, device);

        assertEquals(SignedLedgerSync.Outcome.SYNCED, result.outcome);
        JSONObject stored = journal.metadata("lease");
        assertNotNull(stored.optJSONObject("payload"));
        assertEquals(device.boot, stored.optLong("boot"));
        assertEquals(device.elapsed, stored.optLong("elapsed"));
        assertTrue("the pass takes the snapshot twice: before and after the push", ledger.calls.stream().filter("snapshot"::equals).count() == 2);
    }

    @Test public void aLeaseSignedByAnotherKeyIsRefused() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        // hand the client a lease whose signature was made with a key it did not publish
        ledger.leaseAnswer.put("publicKey", lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair()).getJSONObject("publicKey"));
        FakeJournal journal = new FakeJournal();

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals(SignedLedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertEquals("ترخيص غير صالح.", result.message);
        assertEquals("no lease is stored when it cannot be trusted", 0, journal.metadata("lease").length());
        assertEquals("", journal.metadata("sync").optString("blockedCode"));
    }

    @Test public void aLeaseForAnotherDeviceIsRefused() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease("11111111-2222-3333-4444-555555555555", 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals(SignedLedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertEquals("نطاق الترخيص غير مطابق.", result.message);
    }

    @Test public void aLeaseForAnotherStoreIsRefused() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 99, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();

        assertEquals(SignedLedgerSync.Outcome.RETRY_LATER, run(ledger, journal, new FakeDevice()).outcome);
    }

    @Test public void aQueuedOperationIsPushedAndItsReceiptRecorded() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        JSONObject queued = command("op-1", "epoch-1", INSTALLATION);
        journal.add(queued);
        ledger.receipts.put("op-1", new JSONObject().put("status", "accepted").put("operationId", "op-1"));

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals(SignedLedgerSync.Outcome.SYNCED, result.outcome);
        assertEquals(1, result.accepted);
        assertEquals(0, result.rejected);
        assertEquals("acknowledged", journal.entries.get(0).optString("state"));
        assertTrue(ledger.calls.contains("apply:op-1"));
    }

    @Test public void aRejectedOperationIsRecordedAndNotRetried() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        journal.add(command("op-2", "epoch-1", INSTALLATION));
        ledger.receipts.put("op-2", new JSONObject().put("status", "rejected").put("code", "duplicate_phone"));

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals(0, result.accepted);
        assertEquals(1, result.rejected);
        assertEquals("rejected", journal.entries.get(0).optString("state"));
        assertEquals("duplicate_phone", journal.entries.get(0).optString("error"));
    }

    @Test public void aRefusedOperationBecomesUncertainAndThePassReportsRetry() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        journal.add(command("op-3", "epoch-1", INSTALLATION));
        ledger.refusals.put("op-3", new SyncFailure(500, new JSONObject(), "خطأ مؤقت", "temporary_service_failure"));

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals(SignedLedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertEquals("uncertain", journal.entries.get(0).optString("state"));
        assertTrue("an uncertain entry is never dropped", journal.entries.size() == 1);
    }

    @Test public void anInFlightDependencyDoesNotStopTheRestOfThePass() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        journal.add(command("op-4", "epoch-1", INSTALLATION));
        journal.add(command("op-5", "epoch-1", INSTALLATION));
        ledger.refusals.put("op-4", new SyncFailure(409, new JSONObject(), "لم تصل العملية التي يعتمد عليها هذا الإدخال بعد.", "dependency_pending"));
        ledger.receipts.put("op-5", new JSONObject().put("status", "accepted").put("operationId", "op-5"));

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals("the pass still completes", SignedLedgerSync.Outcome.SYNCED, result.outcome);
        assertEquals(1, result.accepted);
        assertEquals("uncertain", journal.entries.get(0).optString("state"));
        assertEquals("acknowledged", journal.entries.get(1).optString("state"));
    }

    @Test public void anEntryFromAnotherDeviceIsBlockedWithoutAPermit() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        journal.add(command("op-6", "epoch-1", "11111111-2222-3333-4444-555555555555"));

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals("blocked", journal.entries.get(0).optString("state"));
        assertFalse("a blocked entry is never sent", ledger.calls.contains("apply:op-6"));
        assertEquals(0, result.accepted);
    }

    @Test public void anEntryFromAnEarlierRecoveryGenerationIsBlocked() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        FakeJournal journal = new FakeJournal();
        journal.add(command("op-7", "epoch-old", INSTALLATION));

        run(ledger, journal, new FakeDevice());

        assertEquals("blocked", journal.entries.get(0).optString("state"));
        assertFalse(ledger.calls.contains("apply:op-7"));
    }

    @Test public void aServerThatMovedItsRecoveryGenerationStopsThePass() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.leaseAnswer = lease(INSTALLATION, 7, USER, 1_700_000_000_000L, 1_700_000_000_000L + 7L * 86400000, keyPair());
        ledger.snapshotAnswer = new JSONObject().put("epoch", "epoch-2").put("cursor", 5);
        FakeJournal journal = new FakeJournal();
        journal.add(command("op-8", "epoch-1", INSTALLATION));
        // merge() records what the server said; the pass reads recoveryRequired from it
        journal.metadata("cache", new JSONObject().put("epoch", "epoch-2"));
        journal.metadata("sync", new JSONObject().put("recoveryRequired", true));

        SignedLedgerSync.Result result = run(ledger, journal, new FakeDevice());

        assertEquals(SignedLedgerSync.Outcome.RECOVERY_REQUIRED, result.outcome);
        assertFalse(ledger.calls.contains("apply:op-8"));
    }

    @Test public void aFreshLeaseOnThisBootAllowsOfflineEntries() throws Exception {
        FakeJournal journal = new FakeJournal();
        journal.metadata("installation", new JSONObject().put("status", "active").put("generation", 1));
        FakeDevice device = new FakeDevice();
        journal.metadata("lease", new JSONObject()
                .put("payload", new JSONObject().put("generation", 1).put("issuedAt", 1_700_000_000_000L).put("expiresAt", 1_700_000_000_000L + 7L * 86400000))
                .put("elapsed", device.elapsed).put("wall", device.wall).put("boot", device.boot));

        assertTrue(SignedLedgerSync.canAddOffline(journal, device));
    }

    @Test public void aLeaseDoesNotSurviveAReboot() throws Exception {
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        journal.metadata("lease", new JSONObject()
                .put("payload", new JSONObject().put("generation", 1).put("issuedAt", 1_700_000_000_000L).put("expiresAt", 1_700_000_000_000L + 7L * 86400000))
                .put("elapsed", device.elapsed).put("wall", device.wall).put("boot", device.boot));

        device.boot = device.boot + 1;

        assertFalse(SignedLedgerSync.canAddOffline(journal, device));
    }

    @Test public void anExpiredLeaseStopsOfflineEntriesEvenWithTimePassing() throws Exception {
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        journal.metadata("lease", new JSONObject()
                .put("payload", new JSONObject().put("generation", 1).put("issuedAt", 1_700_000_000_000L).put("expiresAt", 1_700_000_000_000L + 1000L))
                .put("elapsed", device.elapsed).put("wall", device.wall).put("boot", device.boot));
        device.elapsed = device.elapsed + 2000;

        assertFalse(SignedLedgerSync.canAddOffline(journal, device));
    }

    @Test public void aBlockedDeviceMayNotAddOfflineEntries() throws Exception {
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        journal.metadata("lease", new JSONObject()
                .put("payload", new JSONObject().put("generation", 1).put("issuedAt", 1_700_000_000_000L).put("expiresAt", 1_700_000_000_000L + 7L * 86400000))
                .put("elapsed", device.elapsed).put("wall", device.wall).put("boot", device.boot));
        journal.metadata("sync", new JSONObject().put("blockedCode", "installation_revoked"));

        assertFalse(SignedLedgerSync.canAddOffline(journal, device));
    }

    @Test public void aClockMovedBackwardsInvalidatesTheLease() throws Exception {
        FakeJournal journal = new FakeJournal();
        FakeDevice device = new FakeDevice();
        journal.metadata("lease", new JSONObject()
                .put("payload", new JSONObject().put("generation", 1).put("issuedAt", 1_700_000_000_000L).put("expiresAt", 1_700_000_000_000L + 7L * 86400000))
                .put("elapsed", device.elapsed).put("wall", device.wall).put("boot", device.boot));
        device.wall = device.wall - 600000;

        assertFalse(SignedLedgerSync.canAddOffline(journal, device));
    }
}
