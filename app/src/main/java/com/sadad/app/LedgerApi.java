package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The signed ledger's whole surface: five exchanges, nothing else. A device enrols, reads the
 * snapshot, takes a lease so it can work offline, submits operations, and asks what became of them.
 * Implemented by {@link LedgerClient}.
 */
interface LedgerApi {
    /** Registers this device's public key. The ledger answers {@code pending} until an admin approves. */
    JSONObject enroll(JSONObject publicKey) throws Exception;

    /** The whole signed ledger, with the epoch and cursor a command must carry. */
    JSONObject snapshot() throws Exception;

    /** A server-signed, time-bounded permission to add entries while offline. */
    JSONObject lease() throws Exception;

    /** Submit one operation. The answer is its receipt: accepted, or rejected with a code. */
    JSONObject apply(JSONObject command, String recoveryPermit) throws Exception;

    /** Receipts for operations already submitted, by operation id. The ledger answers with a list. */
    JSONArray outcomes(JSONArray operationIds) throws Exception;

    /** Latest 200 ledger operations in descending server sequence; excludes login/admin events. */
    JSONArray activity() throws Exception;
}
