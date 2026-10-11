package com.sadad.app;

import org.json.JSONObject;

/**
 * The one failure shape at the sync seam. A transport that could not complete an exchange throws
 * this, so the module that owns the retry/block/logout policy never has to know which adapter it
 * is talking to. {@link #status} is 0 when no response arrived at all.
 */
final class SyncFailure extends Exception {
    static final int NO_RESPONSE = 0;
    final int status;
    final JSONObject body;
    final String code;

    SyncFailure(int status, JSONObject body, String message, String code) {
        super(message == null || message.isEmpty() ? "تعذر الاتصال بالخادم." : message);
        this.status = status;
        this.body = body == null ? new JSONObject() : body;
        this.code = code == null ? "" : code;
    }

    /** A transport-level failure: nothing was answered. */
    static SyncFailure unreachable(String message) { return new SyncFailure(NO_RESPONSE, new JSONObject(), message, "unreachable"); }
}
