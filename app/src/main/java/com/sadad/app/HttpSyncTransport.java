package com.sadad.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The legacy adapter at the sync seam: an authenticated JSON exchange against the store's base
 * address, carrying the publishable key and the session token. This is the only place in the app
 * that sets connect/read timeouts or assembles request headers, so the policy cannot drift between
 * callers again.
 */
final class HttpSyncTransport implements SyncTransport {
    private final SyncSession session;

    HttpSyncTransport(SyncSession session) { this.session = session; }

    @Override public JSONObject request(String method, String path, JSONObject body, String token) throws Exception {
        String base = session.apiBaseUrl().replaceAll("/+$", "");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY);
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = connection.getResponseCode();
            JSONObject payload = read(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code < 200 || code >= 300) throw new SyncFailure(code, payload, payload.optString("message", "تعذر إكمال الطلب."), payload.optString("code", ""));
            return payload;
        } catch (SyncFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw SyncFailure.unreachable(error.getMessage());
        } finally { connection.disconnect(); }
    }

    /** The server's answer, or an envelope carrying its own message when the body was not JSON. */
    private static JSONObject read(InputStream stream) throws Exception {
        if (stream == null) return new JSONObject();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = stream) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                bytes.write(buffer, 0, count);
            }
        }
        String raw = bytes.toString("UTF-8");
        if (raw.isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException invalid) { return new JSONObject().put("message", "استجابة غير صالحة من الخادم."); }
    }
}
