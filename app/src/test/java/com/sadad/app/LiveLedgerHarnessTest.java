package com.sadad.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.util.Arrays;

/**
 * The app's own client classes against the deployed backend, with no device and no emulator: a real
 * P-256 key, the real gateway login, the real signed ledger, and the real projections.
 *
 * <p>It is skipped unless a credentials file is named, so it never runs by accident and never carries
 * a password in the repository:
 *
 * <pre>
 *   ./gradlew testServerDebugUnitTest -Dsadad.live.credentials=/path/to/store-login.json
 * </pre>
 *
 * Its installation key is stored alongside that external file in an owner-only identity file, so
 * approval remains usable on the next run. Run this harness on Linux with an external credentials path.
 * The file is JSON: {@code {"username": "...", "password": "..."}}. It uses
 * the fixed build-time project addresses. It writes one contact to the store it names
 * and archives it again, so point it at a test store.
 */
public class LiveLedgerHarnessTest {
    private static final String GATEWAY = BuildConfig.AUTH_BASE_URL;
    private static final String LEDGER = LedgerClient.BASE;
    private static final String APIKEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY;

    @Test public void theHarnessReusesTheIdentityThatTheAdminApproved() throws Exception {
        Assume.assumeTrue("harness identity storage requires POSIX permissions", java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        java.nio.file.Path directory = Files.createTempDirectory("sadad-harness-identity-");
        java.nio.file.Path file = directory.resolve("identity.json");
        try {
            HarnessSigner first = new HarnessSigner(file), second = new HarnessSigner(file);
            assertEquals(first.id(), second.id()); assertEquals(first.publicKey().toString(), second.publicKey().toString());
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }

    @Test public void theAppClientRunsAgainstTheLiveLedger() throws Exception {
        String path = System.getProperty("sadad.live.credentials", System.getenv("SADAD_LIVE_CREDENTIALS"));
        File credentials = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        Assume.assumeTrue("no credentials file named; set -Dsadad.live.credentials", credentials != null && credentials.isFile());
        JSONObject login = new JSONObject(new String(Files.readAllBytes(Paths.get(credentials.getAbsolutePath())), StandardCharsets.UTF_8));
        String username = login.getString("username"), password = login.getString("password");
        HarnessSigner signer = new HarnessSigner(Paths.get(credentials.getAbsolutePath() + ".identity.json"));

        // ---- 1. The gateway login, which is the only issuer of the Auth envelope.
        JSONObject request = new JSONObject().put("username", username).put("password", password)
                .put("deviceId", signer.id())
                .put("deviceName", "JVM harness").put("staffName", "اختبار آلي");
        JSONObject answer = exchange("POST", GATEWAY + "/mobile/login", request, "", "");
        assertTrue("login refused: " + answer.optString("message"), answer.optBoolean("ok"));
        assertEquals("v3", answer.optString("ledger"));
        String deviceToken = answer.getString("token");
        JSONObject auth = answer.getJSONObject("auth");
        assertFalse(auth.optString("accessToken").isEmpty());
        assertFalse(auth.optString("refreshToken").isEmpty());
        assertTrue(auth.optLong("expiresAt") > System.currentTimeMillis());

        HarnessSession session = new HarnessSession(deviceToken, auth);
        LedgerClient client = new LedgerClient(session, signer, LEDGER);

        // ---- 2. Enrolment. A fresh installation is pending until an admin approves it.
        JSONObject enrolment = client.enroll(signer.publicKey());
        String status = enrolment.optString("status");
        String installationId = enrolment.optString("installationId");
        assertFalse("the ledger did not name the installation", installationId.isEmpty());
        System.out.println("live harness: enrolment " + status + " for installation " + installationId);
        Assume.assumeTrue("this installation is not approved yet; approve it in the dashboard and run again", "active".equals(status));
        int generation = enrolment.getInt("generation");

        // ---- 3. The lease has to verify against the ledger's own key and scope.
        JSONObject lease = client.lease();
        JSONObject payload = SignedLedgerSync.verifyLease(lease, signer.id(), answer.getJSONObject("account").optString("id"), Claims.subjectOf(auth.optString("accessToken")));
        assertEquals(7L * 24 * 60 * 60 * 1000, payload.optLong("expiresAt") - payload.optLong("issuedAt"));

        // ---- 4. The snapshot names the epoch a command has to carry.
        JSONObject before = client.snapshot();
        String epoch = before.optString("epoch");
        assertFalse("the snapshot carries no epoch", epoch.isEmpty());
        LedgerProjection.requireSnapshot(before, answer.getJSONObject("account").optString("id"));
        int contactsBefore = LedgerProjection.view(export(before,new JSONArray()),true,signer.id(),"اختبار آلي").getJSONArray("contacts").length();
        System.out.println("live harness: epoch " + epoch + ", cursor " + before.optLong("cursor") + ", contacts " + contactsBefore);

        // ---- 5. A real write, signed by the app's own canonical text.
        String name = "اختبار الاتصال " + LedgerCommand.now().substring(0, 16).replace('T', ' ');
        JSONObject command = LedgerCommand.of(LedgerCommand.CONTACT_CREATE, LedgerCommand.newEntityId(),
                LedgerCommand.contactPayload(name, "", "اختبار", "أدخله فحص آلي ثم أُرشف"), signer.id(), generation, epoch, new JSONArray(), LedgerCommand.now());
        JSONObject receipt = client.apply(command, "");
        assertEquals("accepted", receipt.optString("status"));
        assertEquals(command.optString("entityId"), receipt.optString("entityId"));
        assertTrue(receipt.optLong("sequence") > 0L);
        System.out.println("live harness: contact accepted, sequence " + receipt.optLong("sequence"));

        // ---- 6. The ledger shows it, and the projection folds it in as a row the screens can read.
        JSONObject after = client.snapshot();
        JSONObject stored = findContact(after, command.optString("entityId"));
        assertNotNull("the accepted contact is not in the snapshot", stored);
        assertEquals(name, stored.optString("name"));
        assertEquals(1L, stored.optLong("version"));
        JSONObject projected = LedgerProjection.view(export(after, new JSONArray()), true, signer.id(), "اختبار آلي");
        assertEquals(contactsBefore + 1, projected.optJSONArray("contacts").length());

        // ---- 7. A refusal, to show the ledger answers rather than accepting anything: a payment
        // against a debt that does not exist is rejected with a code the app turns into a sentence.
        JSONObject orphan = LedgerCommand.of(LedgerCommand.PAYMENT_CREATE, LedgerCommand.newEntityId(),
                LedgerCommand.paymentPayload("11111111-1111-4111-8111-111111111111", 100L, "wallet_jawwal", ""), signer.id(), generation, epoch, new JSONArray(), LedgerCommand.now());
        JSONObject refusal = client.apply(orphan, "");
        assertEquals("rejected", refusal.optString("status"));
        assertEquals("debt_unavailable", refusal.optString("code"));
        assertFalse(LedgerMessages.of(refusal.optString("code")).equals(refusal.optString("code")));

        // ---- 8. Clean up after the test: archive the row this harness created.
        JSONObject archive = LedgerCommand.of("contact.archive", LedgerCommand.newEntityId(),
                new JSONObject().put("contactId", stored.optString("id")).put("reason", "انتهى الفحص الآلي"),
                signer.id(), generation, epoch, new JSONArray(), LedgerCommand.now()).put("expectedVersion", stored.optLong("version"));
        JSONObject archived = client.apply(archive, "");
        assertEquals("accepted", archived.optString("status"));
        JSONObject archivedRow = findContact(client.snapshot(), stored.optString("id"));
        assertNotNull(archivedRow);
        assertTrue("the contact is still listed as active", archivedRow.optBoolean("archived"));
        System.out.println("live harness: cleaned up; the live stack works end to end");
    }

    private static JSONObject findContact(JSONObject snapshot, String id) {
        JSONArray contacts = snapshot.optJSONArray("contacts");
        for (int index = 0; contacts != null && index < contacts.length(); index++) {
            JSONObject row = contacts.optJSONObject(index);
            if (row != null && id.equals(row.optString("id"))) return row;
        }
        return null;
    }

    private static JSONObject export(JSONObject snapshot, JSONArray entries) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("scope", "harness").put("cache", snapshot).put("entries", entries);
    }

    private static JSONObject exchange(String method, String url, JSONObject body, String bearer, String deviceToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("apikey", APIKEY);
            if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (!deviceToken.isEmpty()) connection.setRequestProperty("X-Sadad-Session", deviceToken);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) try (InputStream in = stream) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            String raw = bytes.toString("UTF-8");
            JSONObject payload = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            if (status < 200 || status >= 300) throw new IllegalStateException(method + " " + url + " -> " + status + " " + raw);
            return payload;
        } finally {
            connection.disconnect();
        }
    }

    /** The session the app's own client reads: the device token and the gateway's Auth envelope. */
    private static final class HarnessSession implements LedgerSession {
        private final String token;
        private JSONObject auth;

        HarnessSession(String token, JSONObject auth) { this.token = token; this.auth = auth; }

        @Override public String token() { return token; }
        @Override public JSONObject auth() { return auth; }
        @Override public void updateAuth(JSONObject value) { auth = value; }
        @Override public String authBaseUrl() { return GATEWAY; }
    }

    /** A real P-256 key on the JVM, signing the same canonical text the Keystore signs on the phone. */
    private static final class HarnessSigner implements InstallationSigner {
        private final String id;
        private final KeyPair pair;

        HarnessSigner(java.nio.file.Path file) throws Exception {
            if (Files.exists(file)) {
                JSONObject saved = new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)); id = saved.getString("id");
                java.security.KeyFactory factory = java.security.KeyFactory.getInstance("EC");
                pair = new KeyPair(factory.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64Url.decode(saved.getString("publicKey")))),
                        factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64Url.decode(saved.getString("privateKey")))));
            } else {
                id = java.util.UUID.randomUUID().toString();
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); pair = generator.generateKeyPair();
                Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                Files.write(file, new JSONObject().put("id",id).put("publicKey",Base64Url.encode(pair.getPublic().getEncoded()))
                        .put("privateKey",Base64Url.encode(pair.getPrivate().getEncoded())).toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override public String id() { return id; }

        @Override public JSONObject publicKey() throws Exception {
            ECPoint point = ((ECPublicKey) pair.getPublic()).getW();
            return new JSONObject().put("kty", "EC").put("crv", "P-256")
                    .put("x", Base64Url.encode(fixed(point.getAffineX().toByteArray())))
                    .put("y", Base64Url.encode(fixed(point.getAffineY().toByteArray())));
        }

        @Override public String sign(String method, String route, String raw, String time, String nonce) throws Exception {
            String text = InstallationKey.proofText(method, route, InstallationKey.hash(raw), time, nonce, id);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64Url.encode(derToRaw(signer.sign()));
        }

        private static byte[] fixed(byte[] value) {
            byte[] out = new byte[32];
            int size = Math.min(value.length, 32);
            System.arraycopy(value, value.length - size, out, 32 - size, size);
            return out;
        }

        /** Java signs DER; the ledger and the app both speak 64-byte r||s. */
        private static byte[] derToRaw(byte[] der) {
            int index = 2;
            if ((der[1] & 255) > 127) index = 2 + (der[1] & 127);
            int rLength = der[++index] & 255;
            byte[] r = Arrays.copyOfRange(der, ++index, index + rLength);
            index += rLength;
            int sLength = der[index + 1] & 255;
            byte[] s = Arrays.copyOfRange(der, index + 2, index + 2 + sLength);
            byte[] raw = new byte[64];
            System.arraycopy(fixed(r), 0, raw, 0, 32);
            System.arraycopy(fixed(s), 0, raw, 32, 32);
            return raw;
        }
    }

    /** The JWT claim the ledger scopes an installation to. */
    private static final class Claims {
        static String subjectOf(String accessToken) throws Exception {
            String[] parts = accessToken.split("\\.");
            return new JSONObject(new String(Base64Url.decode(parts[1]), StandardCharsets.UTF_8)).optString("sub");
        }
    }
}
