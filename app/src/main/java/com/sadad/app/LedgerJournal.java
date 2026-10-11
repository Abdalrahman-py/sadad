package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the signed-ledger sync needs from the operation queue: the queued entries, their state
 * transitions, the cached snapshot, the lease, and the worker lock. Backed by
 * {@link EncryptedJournal} in the app, which encrypts every one of those at rest; an in-memory
 * implementation drives the JVM tests.
 *
 * <p>Entry states: {@code queued}, {@code sending}, {@code acknowledged}, {@code rejected},
 * {@code uncertain}, {@code blocked}, {@code reviewed}, {@code cancelled}, {@code confirmed}.
 */
interface LedgerJournal {
    JSONObject metadata(String name) throws Exception;

    void metadata(String name, JSONObject value) throws Exception;

    JSONArray entries() throws Exception;

    JSONObject add(JSONObject command) throws Exception;

    /** Moves an entry to {@code sending}, or returns null when it is already settled. */
    JSONObject startSend(String operationId) throws Exception;

    void state(JSONObject entry, String state, String error) throws Exception;

    /** Folds the server's snapshot and its receipts into the cache and the queue. */
    void merge(JSONObject snapshot) throws Exception;

    /** The worker lock: null when another sync holds it. */
    String acquire() throws Exception;

    void renew(String owner);

    void release(String owner);
}
