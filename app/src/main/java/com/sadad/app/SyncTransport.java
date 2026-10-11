package com.sadad.app;

import org.json.JSONObject;

/**
 * One JSON exchange with the configured backend. The implementation owns the origin, the headers,
 * the timeouts and the error mapping; callers only name the route. Two adapters satisfy this seam:
 * the legacy {@code /mobile/*} protocol today, and the signed ledger behind it.
 */
interface SyncTransport {
    /**
     * @param method GET or POST
     * @param path   route below the base address, e.g. {@code /mobile/sync-delta}
     * @param body   request body, or null for a GET
     * @param token  the store session token to present
     * @throws SyncFailure when the server refuses the exchange or cannot be reached
     */
    JSONObject request(String method, String path, JSONObject body, String token) throws Exception;
}
