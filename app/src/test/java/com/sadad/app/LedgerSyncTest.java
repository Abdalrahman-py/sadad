package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sync protocol as behaviour, against an in-memory ledger and a scripted transport. Nothing here
 * touches Android, which is the entire point of the LedgerSync seam: the retry, block and logout
 * policy is stated once, in one module, and this suite is what holds it to that.
 */
public final class LedgerSyncTest {

    private static JSONObject json(String raw) {
        try { return new JSONObject(raw); }
        catch (JSONException invalid) { throw new AssertionError(invalid); }
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeSession implements SyncSession {
        boolean active = true, forcePassword = false, blocked = false, dirty = false;
        String account = "store-2", token = "token-1", device = "device-1";
        long revision = 4;
        JSONObject accountInfo = json("{}");

        public boolean isActive() { return active; }
        public boolean forcePasswordChange() { return forcePassword; }
        public void setForcePasswordChange(boolean value) { forcePassword = value; }
        public boolean syncBlocked() { return blocked; }
        public void setSyncBlocked(boolean value) { blocked = value; }
        public boolean hasUnsyncedChanges() { return dirty; }
        public void setUnsyncedChanges(boolean value) { dirty = value; }
        public String syncAccountId() { return account; }
        public String token() { return token; }
        public String deviceId() { return device; }
        public long revision() { return revision; }
        public void setRevision(long value) { revision = value; }
        public JSONObject account() { return accountInfo; }
        public void setAccount(JSONObject value) { accountInfo = value; }
        public String apiBaseUrl() { return "https://example.test/api"; }
    }

    private static final class FakeLedger implements SyncLedger {
        boolean deltaReady, pendingSync;
        long changeToken = 1, watermark = 3;
        final JSONObject queued = json("{\"delta\":{\"contacts\":[{\"id\":1}]},\"watermark\":3,\"sequences\":[3]}");
        final JSONObject staged = json("{}");
        JSONObject imported;
        boolean acknowledged, committed, dropped, moreQueued;
        long committedAt = -1;

        public boolean hasPendingSync() { return pendingSync; }
        public long changeToken() { return changeToken; }
        public boolean deltaReady() { return deltaReady; }
        public JSONObject pendingDelta() { return queued; }
        public JSONObject getSnapshot() { return json("{\"contacts\":[],\"debts\":[],\"payments\":[]}"); }
        public void acknowledgeDelta(JSONObject packet) { acknowledged = true; pendingSync = moreQueued; }
        public void importSnapshot(JSONObject snapshot) { imported = snapshot; pendingSync = false; }
        public long outboxWatermark() { return watermark; }
        public void beginSnapshotStage() { staged.remove("contacts"); }
        public void stageSnapshotPage(String table, JSONArray rows) throws JSONException { staged.put(table, rows); }
        public void commitSnapshotStage(long expectedWatermark) { committedAt = expectedWatermark; committed = true; }
        public void dropSnapshotStage() { dropped = true; }
    }

    private interface Answer { JSONObject reply(String method, String path, JSONObject body) throws Exception; }

    private static final class ScriptedTransport implements SyncTransport {
        private final Answer answer;
        final List<String> calls = new ArrayList<>();
        Runnable onCall;

        ScriptedTransport(Answer answer) { this.answer = answer; }

        public JSONObject request(String method, String path, JSONObject body, String token) throws Exception {
            calls.add(method + " " + path);
            if (onCall != null) onCall.run();
            return answer.reply(method, path, body);
        }

        int count(String route) {
            int total = 0;
            for (String call : calls) if (call.endsWith(route)) total++;
            return total;
        }
    }

    private static ScriptedTransport fails(int status, String message) {
        return new ScriptedTransport((method, path, body) -> {
            throw new SyncFailure(status, json("{\"message\":\"" + message + "\"}"), message, "");
        });
    }

    // ------------------------------------------------------------------ upload

    @Test public void aReadyDeltaIsUploadedAndOnlyTheSentRowsAreAcknowledged() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true; ledger.pendingSync = true;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            assertEquals("POST", method);
            assertEquals("/mobile/sync-delta", path);
            assertEquals(4L, body.getLong("baseRevision"));
            assertEquals("device-1:3", body.getString("batchId"));
            assertEquals(1, body.getJSONObject("delta").getJSONArray("contacts").length());
            return json("{\"ok\":true,\"revision\":5}");
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.SYNCED, result.outcome);
        assertTrue(ledger.acknowledged);
        assertFalse(session.hasUnsyncedChanges());
        assertEquals(5L, session.revision());
    }

    @Test public void aLedgerWithMoreQueuedWorkAsksTheCallerToRunAgain() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true; ledger.pendingSync = true;
        ledger.moreQueued = true;   // the store keeps writing while this batch is in flight
        ScriptedTransport transport = new ScriptedTransport((method, path, body) ->
                json("{\"ok\":true,\"revision\":5}"));

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertTrue(result.repeat());
        assertTrue(session.hasUnsyncedChanges());
    }

    @Test public void aFullUploadImportsTheServersAnswer() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = false; ledger.pendingSync = true;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            assertEquals("POST", method);
            assertEquals("/mobile/sync", path);
            assertNotNull(body.optJSONObject("snapshot"));
            return json("{\"ok\":true,\"revision\":6,\"snapshot\":{\"revision\":6,\"contacts\":[],\"account\":{\"id\":\"store-2\",\"name\":\"متجر\"}}}");
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.SYNCED, result.outcome);
        assertNotNull(ledger.imported);
        assertEquals("متجر", session.account().optString("name"));
        assertEquals(6L, session.revision());
        assertFalse(session.hasUnsyncedChanges());
    }

    @Test public void editsThatLandMidFlightStayLocal() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = false; ledger.pendingSync = true;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) ->
                json("{\"ok\":true,\"revision\":6,\"snapshot\":{\"revision\":6,\"contacts\":[]}}"));
        transport.onCall = () -> ledger.changeToken = 99;   // the store keeps working while the upload is in flight

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertNull(ledger.imported);
        assertTrue(session.hasUnsyncedChanges());
        assertEquals(6L, session.revision());
    }

    // ------------------------------------------------------------------ refusal policy

    @Test public void anUnknownRouteIsAProtocolMismatchNotASilentDrop() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true; ledger.pendingSync = true;

        LedgerSync.Result result = LedgerSync.sync(session, ledger, fails(404, "المسار غير موجود"));

        assertEquals(LedgerSync.Outcome.PROTOCOL_MISMATCH, result.outcome);
        assertFalse("the outbox must keep its rows", ledger.acknowledged);
        assertTrue(session.hasUnsyncedChanges());
        assertFalse(result.repeat());
    }

    @Test public void aServerFaultIsRetryable() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true;

        LedgerSync.Result result = LedgerSync.sync(session, ledger, fails(500, "خطأ مؤقت"));

        assertEquals(LedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertTrue(result.repeat());
    }

    @Test public void anUnreachableServerIsRetryable() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            throw SyncFailure.unreachable("انتهت مدة الاتصال");
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertTrue(result.repeat());
    }

    @Test public void aGoneSessionIsReportedSoTheCallerCanLogOut() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true;

        LedgerSync.Result result = LedgerSync.sync(session, ledger, fails(401, "انتهت الجلسة"));

        assertEquals(LedgerSync.Outcome.UNAUTHORIZED, result.outcome);
        assertFalse(result.repeat());
        assertFalse(ledger.acknowledged);
    }

    @Test public void aSignedLedgerStoreIsBlockedWithoutLosingItsEdits() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = true;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            throw new SyncFailure(409, json("{\"code\":\"legacy_ledger_read_only\",\"message\":\"حدّث التطبيق إلى الإصدار الثالث\"}"),
                    "حدّث التطبيق إلى الإصدار الثالث", "legacy_ledger_read_only");
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.BLOCKED, result.outcome);
        assertTrue(session.syncBlocked());
        assertTrue(session.hasUnsyncedChanges());
        assertFalse(ledger.acknowledged);
    }

    @Test public void aServerAheadOfLocalEditsBecomesAConflictTheCallerResolves() {
        FakeSession session = new FakeSession(); session.dirty = true;
        FakeLedger ledger = new FakeLedger(); ledger.deltaReady = false;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            if (path.startsWith("/mobile/snapshot-page")) return json("{\"rows\":[],\"nextAfter\":0,\"done\":true}");
            if (path.equals("/mobile/snapshot")) return json("{\"snapshot\":{\"revision\":9,\"contacts\":[{\"id\":7}]}}");
            throw new SyncFailure(409, json("{\"reloadRequired\":true,\"message\":\"تغيّرت بيانات الحساب\"}"), "تغيّرت بيانات الحساب", "");
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.CONFLICT, result.outcome);
        assertNotNull(result.snapshot);
        assertEquals(9L, result.snapshot.optLong("revision"));
        assertNull("a conflict must never overwrite the local ledger", ledger.imported);
        assertTrue(session.hasUnsyncedChanges());
    }

    // ------------------------------------------------------------------ download

    @Test public void aNewerRevisionIsDownloadedInPagesAndCommittedWithTheWatermarkIntact() {
        FakeSession session = new FakeSession(); session.dirty = false;
        FakeLedger ledger = new FakeLedger(); ledger.watermark = 3;
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            if (path.equals("/mobile/session")) return json("{\"ok\":true,\"revision\":7,\"account\":{\"id\":\"store-2\"}}");
            if (path.contains("table=contacts&after=0")) return json("{\"rows\":[{\"id\":1}],\"nextAfter\":1,\"done\":false}");
            if (path.contains("table=contacts&after=1")) return json("{\"rows\":[],\"nextAfter\":1,\"done\":true}");
            if (path.startsWith("/mobile/snapshot-page")) return json("{\"rows\":[],\"nextAfter\":0,\"done\":true}");
            throw new AssertionError("unexpected route " + path);
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.PULLED, result.outcome);
        assertTrue(ledger.committed);
        assertEquals("the commit must be guarded by the watermark captured before the download", 3L, ledger.committedAt);
        assertTrue(ledger.dropped);
        assertEquals(7L, session.revision());
        assertFalse(session.hasUnsyncedChanges());
        assertTrue(transport.calls.contains("GET /mobile/snapshot-page?table=contacts&after=1&revision=7"));
        assertEquals("two contacts pages and one page per remaining table", 4,
                transport.calls.stream().filter(call -> call.contains("snapshot-page")).count());
    }

    @Test public void anEqualRevisionDownloadsNothing() {
        FakeSession session = new FakeSession(); session.dirty = false;
        FakeLedger ledger = new FakeLedger();
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            if (path.equals("/mobile/session")) return json("{\"ok\":true,\"revision\":4}");
            throw new AssertionError("nothing else should be requested, got " + path);
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.UP_TO_DATE, result.outcome);
        assertFalse(ledger.committed);
    }

    @Test public void aForcedPasswordChangeStopsTheRunBeforeAnyPull() {
        FakeSession session = new FakeSession(); session.dirty = false;
        FakeLedger ledger = new FakeLedger();
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            if (path.equals("/mobile/session")) return json("{\"ok\":true,\"revision\":9,\"forcePasswordChange\":true}");
            throw new AssertionError("nothing else should be requested, got " + path);
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.PASSWORD_CHANGE_REQUIRED, result.outcome);
        assertTrue(session.forcePasswordChange());
        assertFalse(ledger.committed);
    }

    @Test public void aRevisionThatMovesDuringTheDownloadLeavesTheLocalLedgerUntouched() {
        FakeSession session = new FakeSession(); session.dirty = false;
        FakeLedger ledger = new FakeLedger();
        final int[] sessions = {0};
        ScriptedTransport transport = new ScriptedTransport((method, path, body) -> {
            if (path.contains("table=")) return json("{\"rows\":[],\"nextAfter\":0,\"done\":true}");
            if (path.equals("/mobile/session") && ++sessions[0] >= 2) return json("{\"ok\":true,\"revision\":8}");
            return json("{\"ok\":true,\"revision\":7}");
        });

        LedgerSync.Result result = LedgerSync.sync(session, ledger, transport);

        assertEquals(LedgerSync.Outcome.RETRY_LATER, result.outcome);
        assertFalse("a half-downloaded revision must never be committed", ledger.committed);
        assertEquals(4L, session.revision());
    }
}
