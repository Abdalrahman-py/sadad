package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.List;

/**
 * One pass of the signed-ledger protocol: make sure this device is approved, renew the offline lease,
 * take the server's snapshot, push whatever the journal still holds, and take the snapshot again so
 * the receipts that the push produced are folded in.
 *
 * <p>This is the module that replaces the legacy sync engine. The journal holds the queue, the client
 * holds the signing, and the environment holds the clock — so the whole pass is exercised in the JVM
 * against fakes, and the live harness runs it against the real ledger.
 */
final class SignedLedgerSync {
    private static final long LEASE_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** States that need no further sending. */
    private static final List<String> SETTLED = Arrays.asList("confirmed", "rejected", "cancelled", "reviewed");
    /** Refusals that mean this device may not add entries at all until a person intervenes. */
    private static final List<String> BLOCKING = Arrays.asList(
            "installation_revoked", "installation_or_session_revoked", "store_suspended", "password_change_required");

    enum Outcome {
        /** Another pass already holds the lock. */
        IDLE,
        /** The device enrolled, but an admin has not approved it yet. */
        WAITING_APPROVAL,
        /** The server's recovery generation moved; a person must review the receipts. */
        RECOVERY_REQUIRED,
        /** The queue was pushed and the snapshot is current. */
        SYNCED,
        /** Transient: run again. */
        RETRY_LATER
    }

    static final class Result {
        final Outcome outcome;
        final String message;
        final int accepted, rejected;

        private Result(Outcome outcome, String message, int accepted, int rejected) {
            this.outcome = outcome; this.message = message; this.accepted = accepted; this.rejected = rejected;
        }

        static Result of(Outcome outcome, String message) { return new Result(outcome, message, 0, 0); }

        boolean retryable() { return outcome == Outcome.RETRY_LATER || outcome == Outcome.IDLE; }
    }

    private SignedLedgerSync() { }

    static Result sync(LedgerApi ledger, LedgerJournal journal, InstallationSigner installation,
                       DeviceEnvironment device, String storeId, String userId) {
        String owner = null;
        try {
            owner = journal.acquire();
            if (owner == null) return Result.of(Outcome.IDLE, "توجد مزامنة جارية.");

            JSONObject enrolment = ledger.enroll(installation.publicKey());
            if (!installation.id().equals(enrolment.optString("installationId")) || enrolment.optInt("generation") <= 0
                    || !Arrays.asList("active", "pending", "revoked").contains(enrolment.optString("status"))) {
                throw new GeneralSecurityException("استجابة اعتماد الجهاز غير صالحة.");
            }
            journal.metadata("installation", enrolment);
            if (!"active".equals(enrolment.optString("status"))) {
                return Result.of(Outcome.WAITING_APPROVAL, "طلب هذا الجهاز ينتظر موافقة الإدارة.");
            }

            renewLease(ledger, journal, installation, device, storeId, userId);
            journal.merge(ledger.snapshot());
            if (journal.metadata("sync").optBoolean("recoveryRequired")) {
                return Result.of(Outcome.RECOVERY_REQUIRED, "تغيّر إصدار استرداد الخادم؛ راجع الإيصالات قبل الإرسال.");
            }

            int accepted = 0, rejected = 0;
            JSONArray entries = journal.entries();
            String permit = journal.metadata("recovery").optString("permitId", "");
            String epoch = journal.metadata("cache").optString("epoch", "");
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                if (SETTLED.contains(entry.optString("state"))) continue;
                JSONObject command = entry.getJSONObject("command");
                boolean mine = installation.id().equals(command.optString("originInstallationId"));
                if (permit.isEmpty() && !mine) {
                    journal.state(entry, "blocked", "يلزم تصريح استرداد من الإدارة للأصل القديم.");
                    continue;
                }
                if (permit.isEmpty() && !epoch.equals(command.optString("expectedEpoch"))) {
                    journal.state(entry, "blocked", "إدخال من إصدار استرداد سابق؛ راجع الإدارة دون تغيير رقمه.");
                    continue;
                }
                journal.renew(owner);
                JSONObject sending = journal.startSend(command.getString("operationId"));
                if (sending == null) continue;
                try {
                    JSONObject receipt = ledger.apply(new JSONObject(sending.getString("wire")), mine ? "" : permit);
                    boolean ok = "accepted".equals(receipt.optString("status"));
                    if (ok) accepted++; else rejected++;
                    journal.state(sending, ok ? "acknowledged" : "rejected", receipt.optString("code", ""));
                } catch (SyncFailure refusal) {
                    journal.state(sending, refusal.status == 401 || refusal.status == 403 ? "blocked" : "uncertain", refusal.getMessage());
                    // A dependency still in flight, or a permit not yet issued, is not a reason to stop
                    // the whole pass: the other entries can still go.
                    if ("dependency_pending".equals(refusal.code) || "recovery_not_authorized".equals(refusal.code)) continue;
                    throw refusal;
                }
            }

            journal.merge(ledger.snapshot());
            return new Result(Outcome.SYNCED, null, accepted, rejected);
        } catch (SyncFailure refusal) {
            return fail(journal, refusal.code, refusal.getMessage());
        } catch (Exception error) {
            return fail(journal, "", text(error));
        } finally {
            if (owner != null) journal.release(owner);
        }
    }

    /** Asks the ledger for a lease, verifies it, and keeps it where the offline rules can read it. */
    private static void renewLease(LedgerApi ledger, LedgerJournal journal, InstallationSigner installation,
                                   DeviceEnvironment device, String storeId, String userId) throws Exception {
        JSONObject lease = ledger.lease();
        JSONObject payload = verifyLease(lease, installation.id(), storeId, userId);
        if (payload.optInt("generation") != journal.metadata("installation").optInt("generation")) {
            throw new GeneralSecurityException("إصدار ترخيص الجهاز غير مطابق.");
        }
        if (payload.optLong("expiresAt") - payload.optLong("issuedAt") != LEASE_MILLIS) {
            throw new GeneralSecurityException("مدة ترخيص غير صالحة.");
        }
        journal.metadata("lease", new JSONObject().put("envelope", lease).put("payload", payload)
                .put("elapsed", device.elapsedRealtime()).put("wall", device.wallClock()).put("boot", device.bootCount()));
    }

    /**
     * The lease is the server's permission to add entries while offline, so it is verified before it
     * is trusted and before it is stored: signed by the ledger, and scoped to this project, this
     * installation, this store and this user.
     */
    static JSONObject verifyLease(JSONObject lease, String installationId, String storeId, String userId) throws Exception {
        byte[] payload = Base64Url.decode(lease.getString("payload"));
        JSONObject jwk = lease.getJSONObject("publicKey");
        if (!"EC".equals(jwk.optString("kty")) || !"P-256".equals(jwk.optString("crv")) || jwk.has("d")) {
            throw new GeneralSecurityException("مفتاح ترخيص غير صالح.");
        }
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKeyOf(jwk));
        verifier.update(payload);
        if (!verifier.verify(InstallationKey.rawToDer(Base64Url.decode(lease.getString("signature"))))) {
            throw new GeneralSecurityException("ترخيص غير صالح.");
        }
        JSONObject parsed = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (!LedgerClient.PROJECT.equals(parsed.optString("projectRef"))
                || !installationId.equals(parsed.optString("installationId"))
                || !storeId.equals(String.valueOf(parsed.optLong("storeId")))
                || !userId.equals(parsed.optString("userId"))) {
            throw new GeneralSecurityException("نطاق الترخيص غير مطابق.");
        }
        return parsed;
    }

    /**
     * Whether this device may add an entry while offline: the lease must still be valid, must belong
     * to the current boot (no lease survives a restart), and nothing must have blocked the device.
     */
    static boolean canAddOffline(LedgerJournal journal, DeviceEnvironment device) throws Exception {
        JSONObject installation = journal.metadata("installation");
        if (!"active".equals(installation.optString("status")) || installation.optInt("generation") <= 0) return false;
        if (BLOCKING.contains(journal.metadata("sync").optString("blockedCode", ""))) return false;
        JSONObject lease = journal.metadata("lease");
        if (!lease.has("payload") || device.bootCount() < 0 || lease.optLong("boot") != device.bootCount()) return false;
        long elapsed = device.elapsedRealtime() - lease.optLong("elapsed");
        // A clock that moved backwards, or a wall clock that went back, means the lease cannot be trusted.
        if (elapsed < 0 || device.wallClock() < lease.optLong("wall") - 60000) return false;
        JSONObject payload = lease.getJSONObject("payload");
        if (payload.optInt("generation") != installation.optInt("generation")) return false;
        return payload.optLong("issuedAt") + elapsed < payload.optLong("expiresAt");
    }

    private static Result fail(LedgerJournal journal, String code, String message) {
        try {
            journal.metadata("sync", new JSONObject().put("error", message == null ? "" : message)
                    .put("blockedCode", code == null ? "" : code).put("at", System.currentTimeMillis()));
        } catch (Exception ignored) {
            // Reporting the failure must never replace it.
        }
        return Result.of(Outcome.RETRY_LATER, message);
    }

    private static PublicKey publicKeyOf(JSONObject jwk) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger x = new BigInteger(1, Base64Url.decode(jwk.getString("x")));
        BigInteger y = new BigInteger(1, Base64Url.decode(jwk.getString("y")));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve));
    }

    private static String text(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "تعذر إكمال المزامنة." : message;
    }
}
