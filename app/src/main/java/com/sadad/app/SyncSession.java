package com.sadad.app;

import org.json.JSONObject;

/**
 * The session facts the sync algorithm is allowed to read and write. Backed by
 * {@link SessionStore} in the app; an in-memory implementation drives the JVM tests.
 */
interface SyncSession {
    boolean isActive();

    boolean forcePasswordChange();

    void setForcePasswordChange(boolean value);

    boolean syncBlocked();

    void setSyncBlocked(boolean value);

    boolean hasUnsyncedChanges();

    void setUnsyncedChanges(boolean value);

    String syncAccountId();

    String token();

    String deviceId();

    long revision();

    void setRevision(long revision);

    JSONObject account();

    void setAccount(JSONObject account);

    String apiBaseUrl();
}
