package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The sync algorithm for one store account, in one place: push the queued outbox when the ledger is
 * dirty, pull the server's newer revision when it is clean, and turn a refused exchange into the
 * outcome its caller acts on. No Android type crosses this seam, so the whole protocol is reachable
 * from a JVM test against an in-memory ledger and a scripted transport — which is the only reason
 * the retry, block and logout policy can be stated once instead of re-derived per caller.
 *
 * <p>Three things deliberately stay with the caller: the mutual-exclusion lock, the decision to run
 * on a scheduler or on the UI thread, and every user-visible dialog or toast.
 */
final class LedgerSync {
    private static final String[] TABLES = {"contacts", "debts", "payments"};

    /** What happened, in the terms a caller acts on. */
    enum Outcome {
        /** Local changes are uploaded and acknowledged. */
        SYNCED,
        /** The server revision equals the local one; nothing moved. */
        UP_TO_DATE,
        /** A newer revision was downloaded and committed. */
        PULLED,
        /** The server is newer and local edits exist; the snapshot is attached for the caller to resolve. */
        CONFLICT,
        /** This store is on the signed ledger, so the legacy protocol is read-only for it. */
        BLOCKED,
        /** The session is gone: the caller clears it and shows the login screen. */
        UNAUTHORIZED,
        /** The server refused this device for this store. */
        FORBIDDEN,
        /** The store must set a new password before anything else happens. */
        PASSWORD_CHANGE_REQUIRED,
        /** The backend does not serve this route — an app/server version gap, not a transient fault. */
        PROTOCOL_MISMATCH,
        /** Transient, or local edits landed mid-flight: run again. */
        RETRY_LATER,
        /** Anything else, carrying the server's own message. */
        FAILED
    }

    static final class Result {
        final Outcome outcome;
        /** Server message, or null when the outcome speaks for itself. */
        final String message;
        /** Present only for {@link Outcome#CONFLICT}. */
        final JSONObject snapshot;

        private Result(Outcome outcome, String message, JSONObject snapshot) {
            this.outcome = outcome;
            this.message = message;
            this.snapshot = snapshot;
        }

        static Result of(Outcome outcome, String message) { return new Result(outcome, message, null); }

        static Result conflict(String message, JSONObject snapshot) { return new Result(Outcome.CONFLICT, message, snapshot); }

        /** True when running the algorithm again could succeed. */
        boolean repeat() { return outcome == Outcome.RETRY_LATER; }

        /** True when the server's data moved as part of this run. */
        boolean movedData() { return outcome == Outcome.SYNCED || outcome == Outcome.PULLED; }
    }

    private LedgerSync() { }

    /**
     * One pass: upload when the ledger is dirty, otherwise check the server's revision and pull it
     * when it is newer.
     */
    static Result sync(SyncSession session, SyncLedger ledger, SyncTransport transport) {
        if (!session.hasUnsyncedChanges()) return check(session, ledger, transport);
        return push(session, ledger, transport);
    }

    /** Upload the queued outbox: a bounded delta when one is ready, otherwise the whole ledger. */
    static Result push(SyncSession session, SyncLedger ledger, SyncTransport transport) {
        final String account = session.syncAccountId(), token = session.token();
        try {
            if (ledger.deltaReady()) {
                JSONObject packet = ledger.pendingDelta();
                JSONObject body = new JSONObject()
                        .put("baseRevision", session.revision())
                        .put("delta", packet.getJSONObject("delta"))
                        .put("batchId", session.deviceId() + ":" + packet.optLong("watermark"));
                JSONObject answer = transport.request("POST", "/mobile/sync-delta", body, token);
                if (superseded(session, account, token)) return Result.of(Outcome.RETRY_LATER, null);
                ledger.acknowledgeDelta(packet);
                session.setRevision(answer.optLong("revision", session.revision()));
                session.setUnsyncedChanges(ledger.hasPendingSync());
                session.setSyncBlocked(false);
                // More than one delta ready means the ledger moved again while this one was in flight.
                return session.hasUnsyncedChanges() ? Result.of(Outcome.RETRY_LATER, null) : Result.of(Outcome.SYNCED, null);
            }

            long change = ledger.changeToken();
            JSONObject body = new JSONObject().put("baseRevision", session.revision()).put("snapshot", ledger.getSnapshot());
            JSONObject answer = transport.request("POST", "/mobile/sync", body, token);
            if (superseded(session, account, token)) return Result.of(Outcome.RETRY_LATER, null);
            JSONObject snapshot = answer.optJSONObject("snapshot");
            if (snapshot == null) {
                session.setRevision(answer.optLong("revision", session.revision()));
                session.setUnsyncedChanges(false);
                session.setSyncBlocked(false);
                return Result.of(Outcome.SYNCED, null);
            }
            if (ledger.changeToken() != change) {
                // The store kept working while the upload was in flight. Its edits stay local, and the
                // server's answer is adopted as the new baseline.
                session.setRevision(answer.optLong("revision", snapshot.optLong("revision", session.revision())));
                session.setUnsyncedChanges(true);
                return Result.of(Outcome.RETRY_LATER, null);
            }
            ledger.importSnapshot(snapshot);
            JSONObject info = snapshot.optJSONObject("account");
            if (info != null) session.setAccount(info);
            session.setRevision(answer.optLong("revision", snapshot.optLong("revision", session.revision())));
            session.setUnsyncedChanges(false);
            session.setSyncBlocked(false);
            return Result.of(Outcome.SYNCED, null);
        } catch (SyncFailure failure) {
            return refuse(session, transport, failure);
        } catch (Exception error) {
            return Result.of(Outcome.RETRY_LATER, text(error));
        }
    }

    /** Pull only: ask for the server's revision and download it when it is ahead. Never uploads. */
    static Result pull(SyncSession session, SyncLedger ledger, SyncTransport transport) {
        final String account = session.syncAccountId(), token = session.token();
        try {
            JSONObject status = transport.request("GET", "/mobile/session", null, token);
            if (superseded(session, account, token)) return Result.of(Outcome.RETRY_LATER, null);
            adopt(session, status);
            return Result.of(download(session, ledger, transport, status.optLong("revision", session.revision()), account, token), null);
        } catch (SyncFailure failure) {
            return refuse(session, transport, failure);
        } catch (Exception error) {
            return Result.of(Outcome.RETRY_LATER, text(error));
        }
    }

    /**
     * Check the server without uploading. When it is ahead and the ledger is dirty, the server's
     * snapshot comes back as a conflict instead of being committed over local edits.
     */
    private static Result check(SyncSession session, SyncLedger ledger, SyncTransport transport) {
        final String account = session.syncAccountId(), token = session.token();
        try {
            JSONObject status = transport.request("GET", "/mobile/session", null, token);
            if (superseded(session, account, token)) return Result.of(Outcome.RETRY_LATER, null);
            adopt(session, status);
            if (session.forcePasswordChange()) return Result.of(Outcome.PASSWORD_CHANGE_REQUIRED, null);
            if (status.optLong("revision", session.revision()) == session.revision()) return Result.of(Outcome.UP_TO_DATE, null);
            if (session.syncBlocked()) return Result.of(Outcome.BLOCKED, null);
            if (!session.hasUnsyncedChanges()) {
                return Result.of(download(session, ledger, transport, status.optLong("revision", session.revision()), account, token), null);
            }
            JSONObject answer = transport.request("GET", "/mobile/snapshot", null, token);
            JSONObject snapshot = answer.optJSONObject("snapshot");
            if (snapshot == null) return Result.of(Outcome.RETRY_LATER, null);
            return Result.conflict("وجد الخادم نسخة أحدث من سجلات المتجر.", snapshot);
        } catch (SyncFailure failure) {
            return refuse(session, transport, failure);
        } catch (Exception error) {
            return Result.of(Outcome.RETRY_LATER, text(error));
        }
    }

    /** Download a whole revision in bounded pages and commit it, or leave the local ledger untouched. */
    private static Outcome download(SyncSession session, SyncLedger ledger, SyncTransport transport, long revision, String account, String token) throws Exception {
        long watermark = ledger.outboxWatermark();
        ledger.beginSnapshotStage();
        try {
            JSONObject accountInfo = null;
            for (String table : TABLES) {
                long after = 0;
                boolean done = false;
                while (!done) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    JSONObject page = transport.request("GET", "/mobile/snapshot-page?table=" + table + "&after=" + after + "&revision=" + revision, null, token);
                    if (superseded(session, account, token)) throw new IllegalStateException("تغيّر الحساب أثناء التحميل.");
                    ledger.stageSnapshotPage(table, page.getJSONArray("rows"));
                    long next = page.getLong("nextAfter");
                    done = page.getBoolean("done");
                    if (!done && next <= after) throw new IllegalStateException("مؤشر تحميل غير صالح.");
                    after = next;
                    accountInfo = page.optJSONObject("account");
                }
            }
            JSONObject status = transport.request("GET", "/mobile/session", null, token);
            if (status.optLong("revision") != revision) throw new IllegalStateException("تغيّر سجل الخادم أثناء التحميل؛ بقيت النسخة المحلية محفوظة.");
            if (superseded(session, account, token)) throw new IllegalStateException("تغيّر الحساب أثناء التحميل.");
            ledger.commitSnapshotStage(watermark);
            session.setRevision(revision);
            session.setUnsyncedChanges(false);
            session.setSyncBlocked(false);
            if (accountInfo != null) session.setAccount(accountInfo);
            return Outcome.PULLED;
        } finally {
            ledger.dropSnapshotStage();
        }
    }

    /**
     * The single statement of what a refused exchange means. Every caller used to answer this
     * separately, and they disagreed: a 404 ended the run without acknowledging the delta, so the
     * store's edits never left the device and nothing said so.
     */
    private static Result refuse(SyncSession session, SyncTransport transport, SyncFailure failure) {
        int status = failure.status;
        String message = failure.getMessage();
        if (status == SyncFailure.NO_RESPONSE) return Result.of(Outcome.RETRY_LATER, message);
        if (status == 401 || status == 423) return Result.of(Outcome.UNAUTHORIZED, message);
        if (status == 408 || status == 429 || status >= 500) return Result.of(Outcome.RETRY_LATER, message);
        if (status == 404 || status == 405) return Result.of(Outcome.PROTOCOL_MISMATCH, message);
        if (status == 403 || (status == 409 && failure.body.optBoolean("reloadRequired"))) {
            // The server wants this device to adopt its ledger before it will accept anything else.
            JSONObject snapshot = failure.body.optJSONObject("snapshot");
            if (snapshot == null) {
                try {
                    snapshot = transport.request("GET", "/mobile/snapshot", null, session.token()).optJSONObject("snapshot");
                } catch (Exception ignored) { }
            }
            if (snapshot != null) return Result.conflict(message, snapshot);
            if (status == 403) return Result.of(Outcome.FORBIDDEN, message);
        }
        if (status == 409) {
            session.setSyncBlocked(true);
            return Result.of(Outcome.BLOCKED, message);
        }
        return Result.of(Outcome.FAILED, message);
    }

    private static void adopt(SyncSession session, JSONObject status) {
        JSONObject info = status.optJSONObject("account");
        if (info != null) session.setAccount(info);
        session.setForcePasswordChange(status.optBoolean("forcePasswordChange", false));
    }

    /** True when the account or its token changed while the request was in flight. */
    private static boolean superseded(SyncSession session, String account, String token) {
        return !account.equals(session.syncAccountId()) || !token.equals(session.token());
    }

    private static String text(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return "تعذر إكمال المزامنة.";
        return message;
    }
}
