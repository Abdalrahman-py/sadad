package com.sadad.app;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistence checks run only in an isolated QA application. */
public final class LedgerCorrectnessInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().startsWith("com.sadad.qa.")) throw new AssertionError("Use an isolated QA applicationId");
            compoundWriteRollsBack();
            result.putString("stream", "PASS: compound journal rollback\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result);
        }
    }

    private void compoundWriteRollsBack() throws Exception {
        String scope = LedgerQueue.scopeOf("rollback-test", "qa-user");
        String filename = "sadid-v3-" + InstallationKey.hash(scope) + ".db";
        getTargetContext().deleteDatabase(filename);
        EncryptedJournal journal = new EncryptedJournal(getTargetContext(), scope);
        JSONObject cache = new JSONObject().put("epoch", "epoch-1").put("contacts", new JSONArray().put(
                new JSONObject().put("id", "person").put("name", "اختبار"))).put("payments", new JSONArray());
        JSONArray debts = new JSONArray();
        for (int i = 1; i <= 2; i++) debts.put(new JSONObject().put("id", "debt-" + i).put("contact_id", "person")
                .put("direction", "receivable").put("amount_cents", 10000).put("created_at", "2026-10-10T10:00:00Z"));
        journal.metadata("cache", cache.put("debts", debts));
        journal.metadata("installation", new JSONObject().put("status", "active").put("generation", 1));
        journal.metadata("lease", new JSONObject().put("payload", new JSONObject().put("generation", 1).put("issuedAt", 1000).put("expiresAt", 604801000))
                .put("boot", 1).put("elapsed", 1000).put("wall", 1000));
        InstallationSigner signer = new InstallationSigner() {
            public String id() { return "qa-installation"; }
            public JSONObject publicKey() { return new JSONObject(); }
            public String sign(String method, String route, String raw, String time, String nonce) { throw new AssertionError("Offline action"); }
        };
        DeviceEnvironment clock = new DeviceEnvironment() {
            public long bootCount() { return 1; }
            public long elapsedRealtime() { return 1000; }
            public long wallClock() { return 1000; }
        };
        LedgerQueue queue = new LedgerQueue(journal, signer, null, clock, "rollback-test", "qa-user", "اختبار");
        // Fault injection at the external SQLite boundary: fail the second insertion.
        android.database.sqlite.SQLiteDatabase db = getTargetContext().openOrCreateDatabase(filename, 0, null);
        db.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON entries WHEN (SELECT count(*) FROM entries)>=1 BEGIN SELECT RAISE(ABORT,'injected disk failure'); END");
        try {
            boolean failed = false;
            try { queue.payContact("person", 15000, "cash", ""); } catch (android.database.sqlite.SQLiteException expected) { failed = true; }
            if (!failed) throw new AssertionError("Second write must fail");
            if (queue.view().getJSONArray("payments").length() != 0 || !"20000".equals(queue.view().getString("receivableCents")))
                throw new AssertionError("A failed action left a partial payment");
            db.execSQL("DROP TRIGGER fail_second");
            queue.payContact("person", 15000, "cash", "");
            queue.close();
            journal = new EncryptedJournal(getTargetContext(), scope);
            queue = new LedgerQueue(journal, signer, null, clock, "rollback-test", "qa-user", "اختبار");
            if (!"5000".equals(queue.view().getString("receivableCents"))) throw new AssertionError("Payment did not survive reopening");
        } finally { queue.close(); db.close(); getTargetContext().deleteDatabase(filename); }
    }
}
