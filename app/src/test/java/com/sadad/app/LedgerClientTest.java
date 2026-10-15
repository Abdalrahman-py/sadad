package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The signed-ledger contract, held down in the JVM: a real P-256 key, a local server that verifies
 * every request exactly the way {@code sadid-ledger/proof.ts} does, and the shipped {@link LedgerClient}
 * crossing that seam unmodified. Nothing here needs Android, and the signature rule cannot drift
 * without this failing.
 */
public final class LedgerClientTest {

    private ServerSocket listener;
    private Thread server;
    private volatile boolean serving = true;
    private String base;
    private SoftwareSigner signer;
    private FakeSession session;

    private String lastRoute = "", lastMethod = "", lastBody = "", lastAuthorization = "", lastDevice = "";
    private String lastTime = "", lastNonce = "", lastSignature = "", lastInstallation = "";
    private boolean lastProofValid;
    private int status = 200;
    private String answer = "{}";
    private int refreshes;
    private Runnable beforeRefreshResponse = () -> { };

    // ------------------------------------------------------------------ the client's peer

    /** The server side of the proof rule, written from proof.ts rather than from the client. */
    static final class SoftwareSigner implements InstallationSigner {
        private final KeyPair pair;
        private final String id = UUID.randomUUID().toString();

        SoftwareSigner() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
        }

        public String id() { return id; }
        PublicKey publicKeyObject() { return pair.getPublic(); }

        public JSONObject publicKey() throws Exception {
            ECPublicKey key = (ECPublicKey) pair.getPublic();
            return new JSONObject().put("kty", "EC").put("crv", "P-256")
                    .put("x", url(to32(key.getW().getAffineX().toByteArray())))
                    .put("y", url(to32(key.getW().getAffineY().toByteArray())));
        }

        public String sign(String method, String route, String raw, String time, String nonce) throws Exception {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update(canonical(method, route, raw, time, nonce, id).getBytes(StandardCharsets.UTF_8));
            return url(toRaw(signature.sign()));
        }

        boolean verify(String method, String route, String raw, String time, String nonce,
                       String installation, String signature, PublicKey key) throws Exception {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(canonical(method, route, raw, time, nonce, installation).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(InstallationKey.rawToDer(Base64.getUrlDecoder().decode(pad(signature))));
        }

        /** sadid-v3 \n METHOD \n route \n sha256(raw) \n time \n nonce \n installation */
        static String canonical(String method, String route, String raw, String time, String nonce, String installation) throws Exception {
            return "sadid-v3\n" + method.toUpperCase() + "\n" + route + "\n"
                    + InstallationKey.hash(raw) + "\n" + time + "\n" + nonce + "\n" + installation;
        }

        private static byte[] to32(byte[] value) {
            byte[] out = new byte[32];
            int size = Math.min(value.length, 32);
            System.arraycopy(value, value.length - size, out, 32 - size, size);
            return out;
        }

        /** DER SEQUENCE(r, s) -> the 64-byte raw form the wire carries. */
        private static byte[] toRaw(byte[] der) {
            int i = 2;
            if ((der[1] & 0xff) > 127) i = 2 + (der[1] & 0x7f);
            int rLength = der[i + 1] & 0xff, rStart = i + 2;
            int sIndex = rStart + rLength, sLength = der[sIndex + 1] & 0xff, sStart = sIndex + 2;
            byte[] out = new byte[64];
            place(der, rStart, rLength, out, 0);
            place(der, sStart, sLength, out, 32);
            return out;
        }

        private static void place(byte[] der, int start, int length, byte[] out, int offset) {
            int skip = 0;
            while (length - skip > 32) skip++;
            int copy = length - skip;
            System.arraycopy(der, start + skip, out, offset + (32 - copy), copy);
        }

        private static String url(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        private static String pad(String value) { return value + "=".repeat((4 - value.length() % 4) % 4); }
    }

    static final class FakeSession implements LedgerSession {
        JSONObject envelope = new JSONObject();
        String device = "device-token-1";
        String gateway = "http://127.0.0.1:1";

        public String token() { return device; }
        public JSONObject auth() { return envelope; }
        public void updateAuth(JSONObject auth) { envelope = auth; }
        public String authBaseUrl() { return gateway; }
    }

    // ------------------------------------------------------------------ the verifying peer

    @Before public void start() throws Exception {
        signer = new SoftwareSigner();
        session = new FakeSession();
        listener = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        serving = true;
        server = new Thread(this::serve, "verifying-ledger");
        server.setDaemon(true);
        server.start();
        base = "http://127.0.0.1:" + listener.getLocalPort() + "/api";
        session.gateway = "http://127.0.0.1:" + listener.getLocalPort();
    }

    @After public void stop() throws Exception { serving = false; listener.close(); }

    private void serve() {
        while (serving) {
            try (Socket connection = listener.accept()) { handle(connection); }
            catch (Exception stopped) { return; }
        }
    }

    /** ISO-8859-1 is a 1:1 byte map, so the proof still covers exactly the bytes the client sent. */
    private void handle(Socket connection) throws IOException {
        OutputStream out = connection.getOutputStream();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            char[] characters = new char[length];
            int read = 0;
            while (read < length) { int n = reader.read(characters, read, length - read); if (n < 0) break; read += n; }
            String route = requestLine.split(" ")[1].replaceAll("^.*/api", "");
            String method = requestLine.split(" ")[0];
            String raw = new String(new String(characters, 0, read).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (route.equals("/mobile/auth/refresh")) {
                refreshes++;
                beforeRefreshResponse.run();
                respond(out, 200, new JSONObject().put("ok", true).put("auth",
                        new JSONObject().put("accessToken", "renewed-access").put("refreshToken", "r2")
                                .put("expiresAt", System.currentTimeMillis() + 3_600_000)).toString());
                return;
            }
            lastRoute = route;
            lastMethod = method;
            lastBody = raw;
            lastAuthorization = headers.getOrDefault("authorization", "");
            lastDevice = headers.getOrDefault("x-sadad-session", "");

            String installation = headers.getOrDefault("x-sadad-installation", "");
            String time = headers.getOrDefault("x-sadad-time", "");
            String nonce = headers.getOrDefault("x-sadad-nonce", "");
            String signature = headers.getOrDefault("x-sadad-signature", "");
            lastInstallation = installation; lastTime = time; lastNonce = nonce; lastSignature = signature;

            // the rule proof.ts enforces before it looks at anything else
            boolean shaped = installation.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                    && nonce.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                    && time.matches("^\\d{13}$") && signature.length() <= 100
                    && Math.abs(System.currentTimeMillis() - Long.parseLong(time)) <= 300000
                    && Base64.getUrlDecoder().decode(pad(signature)).length == 64;
            // enrolment is proved with the key it is enrolling; everything else with the registered key
            PublicKey key = route.equals("/mobile/v3/enroll")
                    ? keyFrom(new JSONObject(raw).getJSONObject("publicKey")) : signer.publicKeyObject();
            lastProofValid = shaped && signer.verify(method, route, raw, time, nonce, installation, signature, key);
            respond(out, status, answer);
        } catch (Exception failure) {
            String detail = String.valueOf(failure).replace("\"", "'").replace("\n", " ");
            respond(out, 500, "{\"message\":\"harness: " + detail + "\"}");
        }
    }

    private static PublicKey keyFrom(JSONObject jwk) throws Exception {
        java.security.AlgorithmParameters parameters = java.security.AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec spec = parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
        java.math.BigInteger x = new java.math.BigInteger(1, Base64.getUrlDecoder().decode(pad(jwk.getString("x"))));
        java.math.BigInteger y = new java.math.BigInteger(1, Base64.getUrlDecoder().decode(pad(jwk.getString("y"))));
        return java.security.KeyFactory.getInstance("EC").generatePublic(
                new java.security.spec.ECPublicKeySpec(new java.security.spec.ECPoint(x, y), spec));
    }

    private static void respond(OutputStream out, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String pad(String value) { return value + "=".repeat((4 - value.length() % 4) % 4); }

    private LedgerClient client() { return new LedgerClient(session, signer, base); }

    @Test public void anOldClientCannotSendTheNextAccountsCredentials() throws Exception {
        answer = "{\"snapshot\":{}}";
        LedgerClient oldClient = client();
        session.device = "next-account-token";
        try { oldClient.snapshot(); fail("An old queue must refuse a new account's session"); }
        catch (SyncFailure failure) { assertEquals("account_changed", failure.code); }
        assertEquals("", lastRoute);
    }

    @Test public void aDelayedRefreshCannotOverwriteTheNextAccountsAuth() throws Exception {
        session.gateway = base;
        session.envelope = new JSONObject().put("refreshToken", "old-refresh").put("expiresAt", 0);
        JSONObject nextAuth = new JSONObject().put("refreshToken", "next-refresh");
        beforeRefreshResponse = () -> {
            session.device = "next-account-token";
            session.envelope = nextAuth;
        };
        try { client().snapshot(); fail("A delayed refresh must be discarded"); }
        catch (SyncFailure failure) { assertEquals("account_changed", failure.code); }
        assertEquals("next-refresh", session.auth().getString("refreshToken"));
        assertEquals("", lastRoute);
    }

    @Test public void activityIsFetchedThroughTheSignedRoute() throws Exception {
        answer = "{\"ok\":true,\"events\":[{\"sequence\":7,\"commandType\":\"payment.create\"}]}";
        JSONArray events = client().activity();
        assertEquals(7, events.getJSONObject(0).getInt("sequence"));
        assertEquals("/mobile/v3/activity", lastRoute);
        assertEquals("GET", lastMethod);
        assertTrue(lastProofValid);
    }

    // ------------------------------------------------------------------ the contract

    @Test public void enrolmentIsSignedWithTheKeyBeingEnrolled() throws Exception {
        answer = new JSONObject().put("ok", true)
                .put("result", new JSONObject().put("status", "pending").put("generation", 1)).toString();

        JSONObject result = client().enroll(signer.publicKey());

        assertTrue("the signature must verify against the key in the body", lastProofValid);
        assertEquals("/mobile/v3/enroll", lastRoute);
        assertEquals("pending", LedgerClient.status(result));
        assertEquals("POST", lastMethod);
        assertNotNull(result);
    }

    @Test public void everySignedRequestCarriesTheCanonicalProof() throws Exception {
        answer = new JSONObject().put("ok", true)
                .put("snapshot", new JSONObject().put("epoch", "e-1").put("cursor", 7)).toString();

        JSONObject state = client().state();

        assertTrue("the proof must verify with the registered device key", lastProofValid);
        assertEquals("GET", lastMethod);
        assertEquals("", lastBody);
        assertEquals("e-1", state.optString("epoch"));
        assertEquals(7, state.optInt("cursor"));
        assertTrue("the device token must be presented", lastDevice.contains("device-token-1"));
    }

    @Test public void aRefusalArrivesAsTheServersCodeAndMessage() throws Exception {
        status = 409;
        answer = new JSONObject().put("ok", false).put("code", "operation_id_reused")
                .put("message", "رقم العملية مستخدم بمحتوى مختلف.").toString();

        try {
            client().apply(new JSONObject().put("type", "payment.create"), "");
            fail("a rejected operation must not look like success");
        } catch (SyncFailure failure) {
            assertEquals(409, failure.status);
            assertEquals("operation_id_reused", failure.code);
            assertEquals("رقم العملية مستخدم بمحتوى مختلف.", failure.getMessage());
        }
    }

    @Test public void anOfflinePhoneSeesArabicNotTheUnresolvedHost() throws Exception {
        // .invalid never resolves (RFC 6761), so this is the UnknownHostException of a phone offline.
        try {
            new LedgerClient(session, signer, "https://sadad.invalid/api").state();
            fail("an unresolvable host must not look like success");
        } catch (SyncFailure failure) {
            assertEquals(SyncFailure.NO_RESPONSE, failure.status);
            assertEquals("unreachable", failure.code);
            assertEquals("تعذر الوصول إلى الخادم الآن. بياناتك محفوظة على الهاتف وستُزامن تلقائيًا عند عودة الشبكة.", failure.getMessage());
            assertTrue(failure.getCause() instanceof java.net.UnknownHostException);
        }

        session.envelope = new JSONObject().put("refreshToken", "r1").put("expiresAt", 0);
        session.gateway = "https://sadad.invalid";
        try {
            client().state();
            fail("an offline renewal must not look like success");
        } catch (SyncFailure failure) {
            assertEquals("unreachable", failure.code);
            assertTrue(failure.getCause() instanceof java.net.UnknownHostException);
        }
    }

    @Test public void anExpiredEnvelopeIsRenewedBeforeTheRequest() throws Exception {
        session.envelope = new JSONObject().put("accessToken", "stale-access")
                .put("refreshToken", "r1").put("expiresAt", System.currentTimeMillis() - 1000);
        answer = new JSONObject().put("ok", true)
                .put("snapshot", new JSONObject().put("epoch", "e-2").put("cursor", 1)).toString();

        client().state();

        assertEquals("the envelope must be renewed once", 1, refreshes);
        assertEquals("renewed-access", session.auth().optString("accessToken"));
        assertTrue("the renewed token must be presented", lastAuthorization.contains("renewed-access"));
        assertFalse("the stale token must not be presented", lastAuthorization.contains("stale-access"));
    }

    @Test public void aFreshEnvelopeIsNotRenewed() throws Exception {
        session.envelope = new JSONObject().put("accessToken", "fresh")
                .put("refreshToken", "r1").put("expiresAt", System.currentTimeMillis() + 3_600_000);
        answer = new JSONObject().put("ok", true)
                .put("snapshot", new JSONObject().put("epoch", "e-3").put("cursor", 2)).toString();

        client().state();

        assertEquals(0, refreshes);
        assertTrue(lastAuthorization.contains("fresh"));
    }

    @Test public void aLeaseIsReturnedAsASignedEnvelope() throws Exception {
        answer = new JSONObject().put("ok", true).put("lease", new JSONObject()
                .put("payload", "payload-b64").put("signature", "signature-b64")
                .put("publicKey", new JSONObject().put("kty", "EC"))).toString();

        JSONObject lease = client().lease();

        assertTrue(lastProofValid);
        assertEquals("POST", lastMethod);
        assertNotNull(lease);
        assertEquals("payload-b64", lease.optString("payload"));
        assertEquals("signature-b64", lease.optString("signature"));
    }

    @Test public void outcomesArePostedByIds() throws Exception {
        answer = new JSONObject().put("ok", true)
                .put("result", new JSONArray().put(new JSONObject().put("operationId", "op-1").put("status", "accepted"))).toString();

        JSONArray received = new JSONArray();
        received.put("op-1");

        JSONArray receipts = client().outcomes(received);

        assertTrue(lastProofValid);
        assertEquals("/mobile/v3/outcomes", lastRoute);
        assertEquals(1, new JSONObject(lastBody).getJSONArray("operationIds").length());
        assertEquals("accepted", receipts.getJSONObject(0).optString("status"));
    }

    @Test public void theProofCoversTheExactBytes() throws Exception {
        answer = new JSONObject().put("ok", true)
                .put("result", new JSONObject().put("status", "accepted")).toString();
        JSONObject command = new JSONObject().put("type", "contact.create").put("operationId", "op-2");

        client().apply(command, "");

        assertTrue("the bytes actually sent verify", signer.verify(lastMethod, lastRoute, lastBody, lastTime,
                lastNonce, lastInstallation, lastSignature, signer.publicKeyObject()));
        assertFalse("and one changed byte does not", signer.verify(lastMethod, lastRoute, lastBody + " ", lastTime,
                lastNonce, lastInstallation, lastSignature, signer.publicKeyObject()));
    }

    /**
     * The canonical text is the entire wire contract, and the server builds it in proof.ts. This pins the
     * bytes, and pins that the method is case-folded the way the server folds it — including on a device
     * whose locale would fold "i" into a dotted capital, which is how a Turkish phone breaks a signature
     * that every other phone signs correctly.
     */
    @Test public void theCanonicalTextIsPinnedAndLocaleProof() {
        assertEquals("sadid-v3\nPOST\n/mobile/v3/operations\nabc123\ntime-1\nnonce-1\ninstall-1",
                InstallationKey.proofText("POST", "/mobile/v3/operations", "abc123", "time-1", "nonce-1", "install-1"));
        assertEquals(InstallationKey.proofText("POST", "/mobile/v3/operations", "abc123", "time-1", "nonce-1", "install-1"),
                InstallationKey.proofText("post", "/mobile/v3/operations", "abc123", "time-1", "nonce-1", "install-1"));

        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertEquals("sadid-v3\nOPTIONS\n/mobile/v3/operations\nabc123\ntime-1\nnonce-1\ninstall-1",
                    InstallationKey.proofText("options", "/mobile/v3/operations", "abc123", "time-1", "nonce-1", "install-1"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
