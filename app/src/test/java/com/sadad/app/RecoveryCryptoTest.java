package com.sadad.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RecoveryCryptoTest {
    @Test public void recoveryIsAuthenticatedAndDoesNotLeavePasswordsInMemory() throws Exception {
        JSONObject bundle = new JSONObject().put("scope", "project|7|user").put("schemaVersion",1).put("entries",new org.json.JSONArray());
        char[] password = "a test recovery password".toCharArray();
        byte[] sealed = RecoveryCrypto.seal(bundle, password);
        assertArrayEquals(new char[password.length], password);
        char[] openPassword = "a test recovery password".toCharArray();
        assertEquals(bundle.toString(), RecoveryCrypto.open(sealed, openPassword).toString());
        assertArrayEquals(new char[openPassword.length], openPassword);
        char[] wrong = "a wrong recovery password".toCharArray();
        assertThrows(javax.crypto.AEADBadTagException.class, () -> RecoveryCrypto.open(sealed, wrong));
        assertArrayEquals(new char[wrong.length], wrong);
        JSONObject envelope = new JSONObject(new String(sealed, java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = Base64Url.decode(envelope.getString("data")); encrypted[0] ^= 1;
        byte[] changed = envelope.put("data",Base64Url.encode(encrypted)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(javax.crypto.AEADBadTagException.class, () -> RecoveryCrypto.open(changed, "a test recovery password".toCharArray()));
    }

    @Test public void invalidFormatsAndWeakExportPasswordsAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RecoveryCrypto.seal(new JSONObject(), "short".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCrypto.open(new byte[8*1024*1024+1], "a test recovery password".toCharArray()));
    }
}
