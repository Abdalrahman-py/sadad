package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The signed-ledger adapter: five exchanges behind {@link LedgerApi}, and every one of them signed
 * with the device key. The implementation owns the canonical text, the header set, the access-token
 * refresh, the timeouts and the error mapping, so no caller has to know any of it — which is what
 * lets the whole ledger path be tested in the JVM against a fake signer and a scripted server.
 *
 * <p>The origin is fixed on purpose: this adapter only ever talks to this project's signed ledger.
 * The gateway address, which the refresh needs, comes from the session.
 */
final class LedgerClient implements LedgerApi {
    /** The one place the project is named; every address and every lease check derives from it. */
    static final String PROJECT = "vhftjmiltqfwmrszdtgf";
    static final String BASE = "https://" + PROJECT + ".supabase.co/functions/v1/sadid-ledger/api";

    private final LedgerSession session;
    private final InstallationSigner installation;
    private final String base;

    LedgerClient(LedgerSession session, InstallationSigner installation) {
        this(session, installation, BASE);
    }

    /** Explicit origin, so a test can stand a verifying server in front of this client. */
    LedgerClient(LedgerSession session, InstallationSigner installation, String base) {
        this.session = session;
        this.installation = installation;
        this.base = base;
    }

    @Override public JSONObject enroll(JSONObject publicKey) throws Exception {
        return result(request("POST", "/mobile/v3/enroll", new JSONObject().put("publicKey", publicKey), ""));
    }

    /** The ledger state. The caller needs its {@code epoch} and {@code cursor} to build a command. */
    JSONObject state() throws Exception {
        return request("GET", "/mobile/v3/snapshot", null, "").getJSONObject("snapshot");
    }

    @Override public JSONObject snapshot() throws Exception { return state(); }

    @Override public JSONObject lease() throws Exception {
        return request("POST", "/mobile/v3/lease", new JSONObject(), "").getJSONObject("lease");
    }

    @Override public JSONObject apply(JSONObject command, String recoveryPermit) throws Exception {
        return result(request("POST", "/mobile/v3/operations", command, recoveryPermit));
    }

    @Override public JSONArray outcomes(JSONArray operationIds) throws Exception {
        JSONObject answer = request("POST", "/mobile/v3/outcomes", new JSONObject().put("operationIds", operationIds), "");
        JSONArray receipts = answer.optJSONArray("result");
        return receipts == null ? new JSONArray() : receipts;
    }

    /** Enrolment answers {@code {status, generation}}: which side of admin approval this device is on. */
    static String status(JSONObject answer) { return answer.optString("status", ""); }

    private static JSONObject result(JSONObject answer) {
        JSONObject value = answer.optJSONObject("result");
        return value == null ? new JSONObject() : value;
    }

    private JSONObject request(String method, String route, JSONObject body, String recovery) throws Exception {
        refresh();
        String raw = body == null ? "" : body.toString();
        String time = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        HttpURLConnection connection = (HttpURLConnection) new URL(base + route).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // Two headers on purpose: the bearer proves the user, X-Sadad-Session proves the device.
            connection.setRequestProperty("Authorization", "Bearer " + session.auth().optString("accessToken"));
            connection.setRequestProperty("X-Sadad-Session", session.token());
            connection.setRequestProperty("X-Sadad-Installation", installation.id());
            connection.setRequestProperty("X-Sadad-Time", time);
            connection.setRequestProperty("X-Sadad-Nonce", nonce);
            connection.setRequestProperty("X-Sadad-Signature", installation.sign(method, route, raw, time, nonce));
            if (recovery != null && !recovery.isEmpty()) connection.setRequestProperty("X-Sadad-Recovery", recovery);
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(raw.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            JSONObject payload = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) {
                throw new SyncFailure(status, payload, payload.optString("message", "تعذر إتمام الطلب."), payload.optString("code", ""));
            }
            return payload;
        } catch (SyncFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw SyncFailure.unreachable(error.getMessage());
        } finally { connection.disconnect(); }
    }

    /** The envelope is short-lived; renew it before it lapses so a signed request never 401s. */
    private synchronized void refresh() throws Exception {
        JSONObject auth = session.auth();
        if (auth.optLong("expiresAt") > System.currentTimeMillis() + 60000) return;
        String refreshToken = auth.optString("refreshToken");
        if (refreshToken.isEmpty()) return;   // nothing to renew with: let the ledger answer 401
        JSONObject body = new JSONObject().put("refreshToken", refreshToken);
        HttpURLConnection connection = (HttpURLConnection) new URL(session.authBaseUrl().replaceAll("/+$", "") + "/mobile/auth/refresh").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Sadad-Session", session.token());
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            JSONObject payload = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) throw new SyncFailure(status, payload, payload.optString("message", "تعذر تجديد الجلسة."), payload.optString("code", ""));
            session.updateAuth(payload.getJSONObject("auth"));
        } finally { connection.disconnect(); }
    }

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
        catch (Exception invalid) { return new JSONObject().put("message", "استجابة غير صالحة من الخادم."); }
    }
}
