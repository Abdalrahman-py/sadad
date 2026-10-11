package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The ledger facts the sync algorithm is allowed to touch: the queued outbox, the change token that
 * says whether local edits landed while a request was in flight, and the staged download. Backed by
 * {@link SadadDatabase} in the app; an in-memory implementation drives the JVM tests.
 */
interface SyncLedger {
    boolean hasPendingSync();

    long changeToken();

    boolean deltaReady();

    JSONObject pendingDelta() throws JSONException;

    JSONObject getSnapshot() throws JSONException;

    void acknowledgeDelta(JSONObject packet) throws JSONException;

    void importSnapshot(JSONObject snapshot) throws JSONException;

    long outboxWatermark();

    void beginSnapshotStage();

    void stageSnapshotPage(String table, JSONArray rows) throws JSONException;

    void commitSnapshotStage(long expectedWatermark);

    void dropSnapshotStage();
}
