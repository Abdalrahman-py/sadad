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
            localUpgradeKeepsFinancialRowsWithoutLegacySync();
            compoundWriteRollsBack();
            refreshedAuthSurvivesAccountSwitch();
            storeKeysSurviveReopening();
            signedRestorePreservesDataAndCorrections();
            writesRunOffMainThread();
            syncRefreshKeepsOpenForm();
            importKeepsWorkingPast999Rows();
            emptyViewNeverWipesLocalRows();
            result.putString("stream", "PASS: compound journal rollback, saved Auth rotation, store key isolation, legacy SQLite retirement, signed restore/corrections/re-registration/recovery, sync keeps open form, import past 999 rows, no empty-view wipe\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result);
        }
    }

    private void localUpgradeKeepsFinancialRowsWithoutLegacySync() throws Exception {
        String filename = "qa-legacy-retirement.db";
        getTargetContext().deleteDatabase(filename);
        SadadDatabase initial = new SadadDatabase(getTargetContext(), filename);
        initial.saveContact(new JSONObject().put("name", "Standalone row"));
        initial.close();
        android.database.sqlite.SQLiteDatabase fixture = getTargetContext().openOrCreateDatabase(filename, 0, null);
        fixture.execSQL("CREATE TABLE IF NOT EXISTS sync_control(id INTEGER PRIMARY KEY, suppress INTEGER, ready INTEGER)");
        fixture.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox(seq INTEGER PRIMARY KEY,entity TEXT,row_id INTEGER)");
        fixture.execSQL("DROP TRIGGER IF EXISTS queue_contacts_INSERT");
        fixture.execSQL("CREATE TRIGGER queue_contacts_INSERT BEFORE INSERT ON contacts BEGIN SELECT RAISE(ABORT,'obsolete sync trigger'); END");
        fixture.setVersion(8);
        fixture.close();
        SadadDatabase upgraded = new SadadDatabase(getTargetContext(), filename);
        try {
            if (upgraded.getSnapshot().getJSONArray("contacts").length() != 1) throw new AssertionError("Upgrade lost local financial rows");
            upgraded.saveContact(new JSONObject().put("name", "New row"));
            // A real connected view still replaces pre-signed rows; only a fully empty view is refused.
            upgraded.importLedgerView(new JSONObject().put("epoch", "current").put("cursor", 0)
                    .put("contacts", new JSONArray().put(new JSONObject().put("id", "connected").put("name", "Connected row")))
                    .put("debts", new JSONArray()).put("payments", new JSONArray()));
            JSONArray left = upgraded.getSnapshot().getJSONArray("contacts");
            if (left.length() != 1 || !"Connected row".equals(left.getJSONObject(0).optString("name")))
                throw new AssertionError("Signed view never replaced obsolete test rows");
        } finally { upgraded.close(); getTargetContext().deleteDatabase(filename); }
    }

    /**
     * Android 7-10 SQLite (minSdk 24) caps binds at 999, so a per-row bind in removeUnseen breaks
     * the whole import there; on this device (API 30+) it only proves the import is idempotent
     * across re-imports (rows are matched on remote_uuid, never re-inserted).
     */
    private void importKeepsWorkingPast999Rows() throws Exception {
        String filename = "qa-999-rows.db";
        getTargetContext().deleteDatabase(filename);
        SadadDatabase db = new SadadDatabase(getTargetContext(), filename);
        try {
            JSONArray contacts = new JSONArray().put(new JSONObject().put("id", "c1").put("name", "عميل 999"));
            JSONArray debts = new JSONArray().put(new JSONObject().put("id", "d1").put("contact_id", "c1").put("amount_cents", 10000));
            JSONArray payments = new JSONArray();
            for (int i = 0; i < 5000; i++)
                payments.put(new JSONObject().put("id", "p" + i).put("debt_id", "d1").put("amount_cents", 100));
            db.importLedgerView(view("qa-999-epoch", contacts, debts, payments));
            if (db.getSnapshot().getJSONArray("payments").length() != 5000) throw new AssertionError("Large import lost first-import rows");
            payments.remove(0);
            db.importLedgerView(view("qa-999-epoch", contacts, debts, payments));
            if (db.getSnapshot().getJSONArray("payments").length() != 4999)
                throw new AssertionError("Second import over 999 rows failed or duplicated rows");
        } finally { db.close(); getTargetContext().deleteDatabase(filename); }
    }

    /**
     * A view that comes back with zero rows while the phone still holds data is a broken download,
     * not an empty ledger: the local tables must survive it.
     */
    private void emptyViewNeverWipesLocalRows() throws Exception {
        String filename = "qa-empty-view.db";
        getTargetContext().deleteDatabase(filename);
        SadadDatabase db = new SadadDatabase(getTargetContext(), filename);
        try {
            JSONArray contacts = new JSONArray();
            for (int i = 1; i <= 3; i++)
                contacts.put(new JSONObject().put("id", "c" + i).put("name", "عميل " + i));
            db.importLedgerView(view("qa-empty-epoch", contacts, new JSONArray(), new JSONArray()));
            if (db.getSnapshot().getJSONArray("contacts").length() != 3) throw new AssertionError("First import did not store the contacts");
            db.importLedgerView(view("qa-empty-epoch", new JSONArray(), new JSONArray(), new JSONArray()));
            if (db.getSnapshot().getJSONArray("contacts").length() != 3)
                throw new AssertionError("An empty ledger view wiped local rows");
        } finally { db.close(); getTargetContext().deleteDatabase(filename); }
    }

    private static JSONObject view(String epoch, JSONArray contacts, JSONArray debts, JSONArray payments) throws Exception {
        return new JSONObject().put("epoch", epoch).put("cursor", 0)
                .put("contacts", contacts).put("debts", debts).put("payments", payments);
    }

    /**
     * The save paths must never do the journal's disk work on the main thread: StrictMode armed on
     * the main looper with penaltyDeath means a synchronous write here kills the process. The
     * dispatch itself is the only point under the strict policy — the app's own screen rebuilds
     * (which have always read SQLite on the main thread) are not this ticket's scope.
     */
    private void writesRunOffMainThread() throws Exception {
        String store = "qa-strict-store", user = "qa-strict-sub";
        // The access token has to read like a JWT or LedgerQueue cannot resolve the ledger's scope.
        String access = "qa-h." + Base64Url.encode(new JSONObject().put("sub", user).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".sig";
        String scope = LedgerQueue.scopeOf(store, user);
        String journalFile = "sadid-v3-" + InstallationKey.hash(scope) + ".db";
        getTargetContext().deleteDatabase(journalFile);
        getTargetContext().deleteDatabase("sadad.db");
        android.content.SharedPreferences activityPrefs = getTargetContext().getSharedPreferences("MainActivity", 0);
        activityPrefs.edit().remove("demo_data_seeded").putBoolean("welcome_seen", false)
                .putBoolean("notification_permission_prompted", true).putBoolean("biometric_enabled", false).apply();
        SessionStore session = new SessionStore(getTargetContext());
        session.clear();
        session.save(new JSONObject().put("token", "token-" + store).put("expiresAt", Long.MAX_VALUE)
                .put("account", new JSONObject().put("id", store).put("username", store).put("name", store))
                .put("auth", new JSONObject().put("accessToken", access).put("refreshToken", "refresh-strict")));
        EncryptedJournal journal = new EncryptedJournal(getTargetContext(), scope);
        try {
            journal.metadata("installation", new JSONObject().put("status", "active").put("generation", 1));
            journal.metadata("lease", new JSONObject().put("payload", new JSONObject().put("projectRef", LedgerClient.PROJECT).put("installationId", "qa-strict-installation")
                    .put("storeId", store).put("userId", user).put("generation", 1).put("issuedAt", 1000).put("expiresAt", 604801000))
                    .put("boot", 1).put("elapsed", 1000).put("wall", 1000));
            journal.metadata("cache", new JSONObject().put("epoch", "qa-epoch")
                    .put("contacts", new JSONArray().put(new JSONObject().put("id", "person").put("name", "اختبار الصارم")))
                    .put("debts", new JSONArray()).put("payments", new JSONArray()));
        } finally { journal.close(); }
        MainActivity host;
        try {
            startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            host = MainActivity.activeInstance.get();
            if (host == null) throw new AssertionError("MainActivity did not start");
            // The queued contact has to be in the local tables before the write: materialize runs
            // inside afterSignedWrite, which is already too late for the debt's owner lookup.
            host.materializeLedger();
            long personId = host.database.getSnapshot().getJSONArray("contacts").getJSONObject(0).optLong("id");
            if (personId <= 0L) throw new AssertionError("The primed contact never reached the local view");
            runStrictWrite(host, personId);
        } finally { cleanupStrictRun(journalFile); }
    }

    private void runStrictWrite(MainActivity host, long personId) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean saved = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>("لم يصل رد الاستدعاء.");
        JSONObject input = new JSONObject();
        input.put("contactId", personId).put("direction", "receivable")
                .put("amount", "120.50").put("note", "اختبار الكتابة خارج الخيط الرئيسي").put("createdBy", "qa");
        runOnMainSync(() -> {
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().penaltyDeath().build());
            try {
                host.addDebtAsync(input,
                        (success, message) -> { saved.set(success); failure.set(message); done.countDown(); });
            } finally {
                android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX);
            }
        });
        if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("لم يصل رد حفظ الدين خلال 10 ثوانٍ.");
        if (!saved.get()) throw new AssertionError("فشل حفظ الدين: " + failure.get());
        if (host.database.getSnapshot().getJSONArray("debts").length() < 1)
            throw new AssertionError("لم يصل الدين المسجّل إلى الجداول المحلية.");
    }

    private void cleanupStrictRun(String journalFile) {
        new SessionStore(getTargetContext()).clear();
        getTargetContext().getSharedPreferences("sadad.session", 0).edit().remove("primary_database_account").apply();
        getTargetContext().deleteDatabase(journalFile);
    }

    private void syncRefreshKeepsOpenForm() throws Exception {
        String store = "qa-form-store", user = "qa-form-sub";
        String access = "qa-h." + Base64Url.encode(new JSONObject().put("sub", user).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".sig";
        String scope = LedgerQueue.scopeOf(store, user);
        String journalFile = "sadid-v3-" + InstallationKey.hash(scope) + ".db";
        getTargetContext().deleteDatabase(journalFile);
        getTargetContext().deleteDatabase("sadad.db");
        android.content.SharedPreferences activityPrefs = getTargetContext().getSharedPreferences("MainActivity", 0);
        activityPrefs.edit().remove("demo_data_seeded").putBoolean("welcome_seen", false)
                .putBoolean("notification_permission_prompted", true).putBoolean("biometric_enabled", false).apply();
        SessionStore session = new SessionStore(getTargetContext());
        session.clear();
        session.save(new JSONObject().put("token", "token-" + store).put("expiresAt", Long.MAX_VALUE)
                .put("account", new JSONObject().put("id", store).put("username", store).put("name", store))
                .put("auth", new JSONObject().put("accessToken", access).put("refreshToken", "refresh-form")));
        try {
            EncryptedJournal journal = new EncryptedJournal(getTargetContext(), scope);
            journal.metadata("installation", new JSONObject().put("status", "active").put("generation", 1));
            journal.metadata("lease", new JSONObject().put("payload", new JSONObject().put("projectRef", LedgerClient.PROJECT).put("installationId", "qa-form-installation")
                    .put("storeId", store).put("userId", user).put("generation", 1).put("issuedAt", 1000).put("expiresAt", 604801000))
                    .put("boot", 1).put("elapsed", 1000).put("wall", 1000));
            journal.metadata("cache", new JSONObject().put("epoch", "qa-form-epoch")
                    .put("contacts", new JSONArray().put(new JSONObject().put("id", "person").put("name", "اختبار النموذج المفتوح")))
                    .put("debts", new JSONArray()).put("payments", new JSONArray()));
            journal.close();
            startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity host = MainActivity.activeInstance.get();
            if (host == null) throw new AssertionError("MainActivity did not start");
            if (!host.isFormRoute("debt_form") || !host.isFormRoute("contact_form") || !host.isFormRoute("payment_form"))
                throw new AssertionError("A *_form route is not recognized as a form");
            if (host.isFormRoute("contacts") || host.isFormRoute("home") || host.isFormRoute("contact_detail"))
                throw new AssertionError("A non-form route is marked as a form");
            try {
                host.materializeLedger();
                final JSONArray contacts = host.database.getSnapshot().getJSONArray("contacts");
                if (contacts.length() < 1) throw new AssertionError("The primed contact is missing");
                final long personId = contacts.getJSONObject(0).optLong("id");
                if (personId <= 0L) throw new AssertionError("The primed contact has no local row id");
                // One main-thread block: the activity's own sync (fake token, so it can end in
                // "sign in required" and navigate) cannot interleave between opening, typing and refreshing.
                runOnMainSync(() -> {
                    host.screens.contactId = personId;
                    host.show("debt_form");
                    if (!"debt_form".equals(host.currentRoute())) throw new AssertionError("The debt form did not open");
                    android.widget.EditText amount = firstEditor(host);
                    if (amount == null) throw new AssertionError("No amount field on the debt form");
                    amount.setText("42.50");
                    if (!"42.50".equals(amount.getText().toString())) throw new AssertionError("Typing did not reach the field");
                    // The path the posts call when a sync finishes. Before the fix this rebuilt the form and cleared the field.
                    host.refreshUnlessEditing();
                    android.widget.EditText still = firstEditor(host);
                    if (still != amount || !"42.50".equals(still.getText().toString()))
                        throw new AssertionError("A finished sync wiped the text typed into the open debt form");
                });
                runOnMainSync(() -> {
                    // Control: a user-path refresh still rebuilds, so the guard above protects something real.
                    if (!"debt_form".equals(host.currentRoute())) host.show("debt_form");
                    android.widget.EditText amount = firstEditor(host);
                    amount.setText("42.50");
                    host.refreshCurrentScreen();
                    if (firstEditor(host) == amount)
                        throw new AssertionError("refreshCurrentScreen did not rebuild the view");
                });
            } finally { runOnMainSync(host::finish); }
        } finally { cleanupStrictRun(journalFile); }
    }

    /** The first editable text box in the rebuild view order — on the debt form this is the amount field. */
    private static android.widget.EditText firstEditor(MainActivity host) {
        android.view.View root = host.findViewById(android.R.id.content);
        if (!(root instanceof android.view.ViewGroup)) return null;
        return firstEditor((android.view.ViewGroup) root);
    }

    private static android.widget.EditText firstEditor(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child instanceof android.widget.EditText) return (android.widget.EditText) child;
            if (child instanceof android.view.ViewGroup) {
                android.widget.EditText found = firstEditor((android.view.ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void storeKeysSurviveReopening() throws Exception {
        InstallationKey first = new InstallationKey(getTargetContext(), "qa-store-a");
        InstallationKey second = new InstallationKey(getTargetContext(), "qa-store-b");
        if (first.id().equals(second.id()) || first.publicKey().toString().equals(second.publicKey().toString()))
            throw new AssertionError("Stores share an installation identity or signing key");
        InstallationKey reopened = new InstallationKey(getTargetContext(), "qa-store-a");
        if (!first.id().equals(reopened.id()) || !first.publicKey().toString().equals(reopened.publicKey().toString()))
            throw new AssertionError("Store identity changed after reopening");
    }

    private void signedRestorePreservesDataAndCorrections() throws Exception {
        String store = "777", scope = LedgerQueue.scopeOf(store, "qa-restore");
        String journalFile = "sadid-v3-" + InstallationKey.hash(scope) + ".db", viewFile = "qa-restore-view.db";
        getTargetContext().deleteDatabase(journalFile); getTargetContext().deleteDatabase(viewFile);
        SadadDatabase local = new SadadDatabase(getTargetContext(), viewFile);
        EncryptedJournal journal = new EncryptedJournal(getTargetContext(), scope);
        try {
            InstallationKey key = new InstallationKey(getTargetContext(), store);
            LedgerQueue queue = new LedgerQueue(journal, key, null, new AndroidEnvironment(getTargetContext()), store, "qa-restore", "اختبار");
            local.saveContact(new JSONObject().put("name", "Saved before download"));
            if (queue.hasSnapshot()) local.importLedgerView(queue.view());
            if (local.getSnapshot().getJSONArray("contacts").length() != 1) throw new AssertionError("First failed download cleared saved data");
            JSONObject snapshot = new JSONObject().put("schemaVersion",1).put("storeId",777).put("currency","ILS").put("epoch","qa-epoch").put("cursor",5)
                    .put("contacts",new JSONArray().put(new JSONObject().put("id","c1").put("name","First")).put(new JSONObject().put("id","c2").put("name","Second")))
                    .put("debts",new JSONArray().put(new JSONObject().put("id","d1").put("contact_id","c1").put("amount_cents",10000).put("direction","receivable"))
                            .put(new JSONObject().put("id","d2").put("contact_id","c2").put("amount_cents",20000).put("direction","receivable")))
                    .put("payments",new JSONArray().put(new JSONObject().put("id","p1").put("debt_id","d1").put("amount_cents",2500).put("method","cash")))
                    .put("debtBalances",new JSONArray().put(new JSONObject().put("debtId","d1").put("amountCents",10000).put("paidCents",2500).put("remainingCents",7500).put("version",1))
                            .put(new JSONObject().put("debtId","d2").put("amountCents",17000).put("paidCents",0).put("remainingCents",17000).put("version",2)))
                    .put("adjustments",new JSONArray()).put("reversals",new JSONArray()).put("outcomes",new JSONArray());
            LedgerProjection.requireSnapshot(snapshot,store); journal.merge(snapshot); local.importLedgerView(queue.view());
            JSONObject displayed = local.getSnapshot();
            if (displayed.getJSONArray("contacts").length()!=2 || displayed.getJSONArray("debts").length()!=2 || displayed.getJSONArray("payments").length()!=1)
                throw new AssertionError("Signed snapshot lost rows");
            double remaining = 0; JSONArray debts = displayed.getJSONArray("debts");
            for (int i=0;i<debts.length();i++) remaining += debts.getJSONObject(i).getDouble("remaining");
            if (Math.abs(remaining-245.0)>0.001) throw new AssertionError("Local display ignored corrected principal");
            JSONObject broken = new JSONObject(snapshot.toString()); broken.remove("contacts");
            boolean refused = false;
            try { LedgerProjection.requireSnapshot(broken,store); journal.merge(broken); } catch (org.json.JSONException expected) { refused=true; }
            if (!refused || local.getSnapshot().getJSONArray("contacts").length()!=2) throw new AssertionError("Incomplete download cleared rows");
            JSONObject command = LedgerCommand.of(LedgerCommand.CONTACT_CREATE,LedgerCommand.newEntityId(),LedgerCommand.contactPayload("Pending","","عميل",""),
                    key.id(),1,"qa-epoch",new JSONArray(),LedgerCommand.now());
            journal.add(command); String original = journal.entries().getJSONObject(0).getString("wire");
            InstallationKey.registerAgain(getTargetContext(),store);
            InstallationKey replacement = new InstallationKey(getTargetContext(),store);
            if (key.id().equals(replacement.id()) || key.publicKey().toString().equals(replacement.publicKey().toString())) throw new AssertionError("Registration reused revoked key");
            journal.close(); journal = new EncryptedJournal(getTargetContext(),scope);
            if (!original.equals(journal.entries().getJSONObject(0).getString("wire"))) throw new AssertionError("Registration rewrote or lost pending command");
            JSONObject backup = journal.exportPlain(); journal.importPlain(backup);
            if (journal.entries().length()!=1) throw new AssertionError("Recovery duplicated pending command");
            JSONObject wrongScope = new JSONObject(backup.toString()).put("scope",LedgerQueue.scopeOf("888","qa-restore"));
            refused=false;try{journal.importPlain(wrongScope);}catch(IllegalArgumentException expected){refused=true;}
            if(!refused || journal.entries().length()!=1)throw new AssertionError("Cross-store recovery accepted");
            JSONObject recovered = new JSONObject(snapshot.toString()).put("epoch","qa-restored"); journal.merge(recovered);
            if(!journal.metadata("sync").optBoolean("recoveryRequired") || !"qa-epoch".equals(journal.metadata("cache").getString("epoch")))
                throw new AssertionError("Changed epoch silently replaced cache");
            journal.acceptEpoch(recovered);
            if(!original.equals(journal.entries().getJSONObject(0).getString("wire")))throw new AssertionError("Epoch review rewrote original command");
        } finally { journal.close(); local.close(); getTargetContext().deleteDatabase(journalFile); getTargetContext().deleteDatabase(viewFile); }
    }

    private void refreshedAuthSurvivesAccountSwitch() throws Exception {
        SessionStore session = new SessionStore(getTargetContext());
        session.clear();
        session.save(login("qa-a", "refresh-a1"));
        session.updateAuth(new JSONObject().put("accessToken", "access-a2").put("refreshToken", "refresh-a2"));
        session.save(login("qa-b", "refresh-b1"));
        if (!session.activateSavedAccount("qa-a")) throw new AssertionError("Cannot restore account A");
        if (!"refresh-a2".equals(session.auth().optString("refreshToken"))) throw new AssertionError("Restored a stale rotated token");
        session.save(new JSONObject().put("token", "legacy-token").put("account", new JSONObject().put("id", "qa-a")));
        if (session.auth().length() != 0) throw new AssertionError("A login without Auth retained another login's credentials");
        session.clear(); session.removeSavedAccount("qa-a"); session.removeSavedAccount("qa-b");
    }

    private JSONObject login(String id, String refresh) throws Exception {
        return new JSONObject().put("token", "token-" + id).put("expiresAt", Long.MAX_VALUE)
                .put("account", new JSONObject().put("id", id).put("username", id).put("name", id))
                .put("auth", new JSONObject().put("accessToken", "access-" + id).put("refreshToken", refresh));
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
        journal.metadata("lease", new JSONObject().put("payload", new JSONObject().put("projectRef", LedgerClient.PROJECT).put("installationId", "qa-installation")
                .put("storeId", "rollback-test").put("userId", "qa-user").put("generation", 1).put("issuedAt", 1000).put("expiresAt", 604801000))
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
