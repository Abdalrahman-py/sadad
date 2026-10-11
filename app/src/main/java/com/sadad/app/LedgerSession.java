package com.sadad.app;

import org.json.JSONObject;

/**
 * What a signed request needs from the session: the device token the gateway issued, the Auth
 * envelope it issued beside it, a way to replace that envelope after a refresh, and the base address
 * the refresh lives under. Backed by {@link SessionStore} in the app.
 */
interface LedgerSession {
    /** The device session token, sent as {@code X-Sadad-Session}. */
    String token();

    /** The Auth envelope: {@code accessToken}, {@code refreshToken}, {@code expiresAt}. */
    JSONObject auth();

    void updateAuth(JSONObject auth) throws Exception;

    /** Base address of the gateway, where {@code /mobile/auth/refresh} lives. */
    String apiBaseUrl();
}
