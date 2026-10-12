package com.sadad.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class SessionStoreTest {
    @Test public void anOldServerPreferenceCannotRedirectTheStoreSession() {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return "https://old.example/api";
                    throw new AssertionError("Unexpected preferences operation: " + method.getName());
                });
        SessionStore session = new SessionStore(preferences);
        assertEquals(BuildConfig.API_BASE_URL, session.apiBaseUrl());
    }

    @Test public void aFourthAccountIsRejectedBeforeTheActiveSessionChanges() throws Exception {
        TestPreferences storage = new TestPreferences();
        storage.values.put("account", "{\"id\":\"1\"}");
        storage.values.put("saved_accounts", new JSONArray().put(new JSONObject().put("id", "1"))
                .put(new JSONObject().put("id", "2")).put(new JSONObject().put("id", "3")).toString());
        SessionStore session = new SessionStore(storage.open());
        assertThrows(IllegalStateException.class, () -> session.save(new JSONObject().put("token", "new-token")
                .put("account", new JSONObject().put("id", "4"))));
        assertEquals("1", session.account().getString("id"));
    }

    @Test public void anIncompleteLoginCannotReplaceTheCurrentAccount() throws Exception {
        TestPreferences storage = new TestPreferences();
        storage.values.put("account", "{\"id\":\"1\"}");
        SessionStore session = new SessionStore(storage.open());
        assertThrows(IllegalArgumentException.class, () -> session.save(new JSONObject().put("token", "")
                .put("account", new JSONObject())));
        assertEquals("1", session.account().getString("id"));
    }
}
