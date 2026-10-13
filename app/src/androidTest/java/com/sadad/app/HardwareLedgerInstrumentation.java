package com.sadad.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.Signature;
import java.util.UUID;

/** Device-only checks for the server edition; live probing never submits ledger operations. */
public final class HardwareLedgerInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle args) { arguments = args; super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        try {
            check("com.sadad.qa".equals(getTargetContext().getPackageName()), "QA package required");
            check(!BuildConfig.STANDALONE_MODE, "Server edition required");
            SessionStore session = new SessionStore(getTargetContext());
            getTargetContext().getSharedPreferences("sadad.session", 0).edit()
                    .putString("api_base_url", "http://localhost:9999/api").commit();
            check(BuildConfig.API_BASE_URL.equals(session.apiBaseUrl()), "Saved URL overrides the embedded server");
            String storeId = "hardware-qa";
            InstallationKey installation = new InstallationKey(getTargetContext(), storeId);
            String id = installation.id();
            check(id.equals(new InstallationKey(getTargetContext(), storeId).id()), "Installation identity changed");
            check("P-256".equals(installation.publicKey().getString("crv")), "Wrong signing curve");
            String time = String.valueOf(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
            byte[] signed = InstallationKey.unb64(installation.sign("post", "/hardware-check", "{}", time, nonce));
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            String alias = getTargetContext().getSharedPreferences("sadid.installation", 0)
                    .getString("alias." + InstallationKey.hash(LedgerClient.PROJECT + "|" + storeId), "");
            check(keys.getKey(alias, null).getEncoded() == null, "Private key is exportable");
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(keys.getCertificate(alias).getPublicKey());
            verifier.update(InstallationKey.proofText("POST", "/hardware-check", InstallationKey.hash("{}"), time, nonce, id).getBytes(StandardCharsets.UTF_8));
            check(verifier.verify(InstallationKey.rawToDer(signed)), "Device signature does not verify");
            report.append("PASS: stable installation identity, non-exportable P-256 key and verified request signature\n");
            journalChecks(installation, keys);
            report.append("PASS: encrypted queue survives reopen; recovery round trip and wrong-password rejection\n");
            AndroidEnvironment device = new AndroidEnvironment(getTargetContext());
            check(device.bootCount() >= 0 && device.elapsedRealtime() > 0, "Offline lease clock unavailable");
            MainActivity host = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            runOnMainSync(() -> host.show("login")); waitForIdleSync();
            check(!containsServerField(host.getWindow().getDecorView()), "Server URL remains visible");
            report.append("PASS: device boot clock; login has no server URL field\n");
            final MainActivity[] abandoned = { null };
            runOnMainSync(() -> abandoned[0] = new MainActivity());
            java.lang.reflect.Field worker = MainActivity.class.getDeclaredField("network"); worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService) worker.get(abandoned[0])).shutdown();
            java.lang.reflect.Method dispatch = MainActivity.class.getDeclaredMethod("executeNetwork", Runnable.class); dispatch.setAccessible(true);
            dispatch.invoke(abandoned[0], (Runnable) () -> { throw new AssertionError("Closed activity accepted network work"); });
            report.append("PASS: late callback after worker shutdown does not crash\n");
            if (arguments != null && Boolean.parseBoolean(String.valueOf(arguments.get("live")))) liveProbe(host, report);
            result.putString("stream", report.toString()); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", report + "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result);
        }
    }

    private void journalChecks(InstallationKey installation, KeyStore keys) throws Exception {
        String scope = "hardware-qa|" + UUID.randomUUID(), digest = InstallationKey.hash(scope);
        EncryptedJournal journal = null;
        try {
            journal = new EncryptedJournal(getTargetContext(), scope);
            JSONObject command = LedgerCommand.of(LedgerCommand.CONTACT_CREATE, LedgerCommand.newEntityId(),
                    LedgerCommand.contactPayload("HARDWARE_PRIVATE_MARKER", "", "", ""), installation.id(),
                    1, "qa-epoch", new JSONArray(), LedgerCommand.now());
            journal.add(command); journal.close(); journal = new EncryptedJournal(getTargetContext(), scope);
            check(journal.entries().getJSONObject(0).getString("wire").equals(command.toString()), "Queued operation changed after reopen");
            try (SQLiteDatabase db = getTargetContext().openOrCreateDatabase("sadid-v3-" + digest + ".db", 0, null);
                 Cursor cursor = db.rawQuery("SELECT data FROM entries", null)) {
                check(cursor.moveToFirst(), "Encrypted row missing");
                check(!new String(cursor.getBlob(0), StandardCharsets.ISO_8859_1).contains("HARDWARE_PRIVATE_MARKER"), "Plaintext journal entry");
            }
            JSONObject bundle = journal.exportPlain();
            byte[] sealed = RecoveryCrypto.seal(bundle, "hardware-test-passphrase".toCharArray());
            check(RecoveryCrypto.open(sealed, "hardware-test-passphrase".toCharArray()).getJSONArray("entries").length() == 1, "Recovery lost the queued operation");
            boolean refused = false;
            try { RecoveryCrypto.open(sealed, "incorrect-passphrase".toCharArray()); }
            catch (javax.crypto.AEADBadTagException expected) { refused = true; }
            check(refused, "Recovery accepted an incorrect password");
        } finally {
            if (journal != null) journal.close();
            getTargetContext().deleteDatabase("sadid-v3-" + digest + ".db");
            if (keys.containsAlias("sadid.journal." + digest)) keys.deleteEntry("sadid.journal." + digest);
        }
    }

    private void liveProbe(MainActivity host, StringBuilder report) throws Exception {
        File credentials = new File(getTargetContext().getFilesDir(), "device-login.json");
        if (credentials.isFile()) {
            JSONObject login;
            try { login = new JSONObject(new String(Files.readAllBytes(credentials.toPath()), StandardCharsets.UTF_8)); }
            finally { credentials.delete(); }
            JSONObject request = new JSONObject().put("username", login.getString("username")).put("password", login.getString("password"))
                    .put("deviceId", host.sessions.deviceId()).put("deviceName", "Sadad hardware QA")
                    .put("staffName", "Hardware QA");
            java.lang.reflect.Method gateway = MainActivity.class.getDeclaredMethod("gatewayRequest", String.class, String.class, JSONObject.class, String.class);
            gateway.setAccessible(true);
            JSONObject answer;
            try { answer = (JSONObject) gateway.invoke(host, "POST", "/mobile/login", request, ""); }
            catch (java.lang.reflect.InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause.getClass().getSimpleName().equals("ApiFailure")) {
                    report.append("LIVE LOGIN REFUSED: ").append(cause.getMessage()).append('\n');
                    return;
                }
                throw error;
            }
            check(!LedgerQueue.subjectOf(answer.optJSONObject("auth") == null ? "" : answer.getJSONObject("auth").optString("accessToken")).isEmpty(),
                    "Gateway omitted the signed ledger user");
            host.sessions.save(answer);
        }
        check(!LedgerQueue.subjectOf(host.sessions.auth().optString("accessToken")).isEmpty(),
                "Sign into the QA app before the live probe, or supply device-login.json");
        check(!new SessionStore(getTargetContext()).token().isEmpty(), "Encrypted device session did not persist");
        check(!host.sessions.auth().optString("accessToken").isEmpty(), "Gateway omitted the Auth envelope");
        report.append("PASS: live authenticated session persists; signed ledger\n");
        LedgerQueue queue = LedgerQueue.open(getTargetContext(), host.sessions);
        try {
                SignedLedgerSync.Result sync;
                long deadline = android.os.SystemClock.elapsedRealtime() + 210000;
                do {
                    synchronized (SyncScheduler.LOCK) { sync = queue.sync(); }
                    if (sync.outcome != SignedLedgerSync.Outcome.IDLE) break;
                    Thread.sleep(1000);
                } while (android.os.SystemClock.elapsedRealtime() < deadline);
                report.append("LIVE SIGNED SYNC: ").append(sync.outcome).append("; ").append(sync.message).append('\n');
                boolean revoked = Boolean.parseBoolean(String.valueOf(arguments.get("revoked")));
                check(sync.outcome == (revoked ? SignedLedgerSync.Outcome.REGISTRATION_REQUIRED : SignedLedgerSync.Outcome.SYNCED),
                        "Unexpected live sync result: " + sync.outcome + "; " + sync.message);
                check(!revoked || queue.hasSnapshot(), "Revocation discarded the downloaded snapshot");
                synchronized (SyncScheduler.LOCK) {
                    check(queue.view().optJSONArray("contacts") != null, "Snapshot was not projected");
                    JSONObject view = queue.view();
                    host.database.importLedgerView(view);
                    JSONObject saved = host.database.getSnapshot();
                    check(saved.getJSONArray("contacts").length() == view.getJSONArray("contacts").length(), "Downloaded contacts missing from SQLite");
                    check(saved.getJSONArray("debts").length() == view.getJSONArray("debts").length(), "Downloaded debts missing from SQLite");
                    check(saved.getJSONArray("payments").length() == view.getJSONArray("payments").length(), "Downloaded payments missing from SQLite");
                    runOnMainSync(() -> host.show("home")); waitForIdleSync();
                    report.append(revoked ? "PASS: revoked installation preserves the displayed ledger: contacts="
                            : "PASS: signed lease, downloaded ledger and committed visible database: contacts=")
                            .append(saved.getJSONArray("contacts").length()).append(", debts=").append(saved.getJSONArray("debts").length())
                            .append(", payments=").append(saved.getJSONArray("payments").length()).append("; no ledger operations submitted\n");
                }
        } finally { queue.close(); }
    }

    private boolean containsServerField(android.view.View view) {
        if (view instanceof android.widget.EditText) {
            android.widget.EditText input = (android.widget.EditText) view;
            if (String.valueOf(input.getHint()).contains("رابط الخادم") || input.getText().toString().contains("supabase.co")) return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsServerField(group.getChildAt(i))) return true;
        }
        return false;
    }

    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
