package com.sadad.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** External Android preferences boundary with atomic editor commits. */
final class TestPreferences {
    final Map<String, Object> values = new HashMap<>();
    SharedPreferences open() {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("edit")) return editor();
            if (name.startsWith("get") && args != null && args.length == 2) return values.getOrDefault(args[0], args[1]);
            if (name.equals("contains")) return values.containsKey(args[0]);
            throw new AssertionError("Unexpected preferences call: " + name);
        });
    }
    private SharedPreferences.Editor editor() {
        Map<String, Object> pending = new HashMap<>();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("put")) { pending.put((String) args[0], args[1]); return proxy; }
            if (name.equals("remove")) { pending.put((String) args[0], null); return proxy; }
            if (name.equals("commit") || name.equals("apply")) {
                pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                return name.equals("commit") ? true : null;
            }
            throw new AssertionError("Unexpected editor call: " + name);
        });
    }
}
