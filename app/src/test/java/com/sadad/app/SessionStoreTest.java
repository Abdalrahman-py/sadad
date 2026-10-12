package com.sadad.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
}
