package com.sadad.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the server bearer token encrypted with an Android Keystore key. */
final class SessionStore implements LedgerSession {
    private static final String PREFS = "sadad.session";
    private static final String KEY_ALIAS = "sadad.session.aes";
    /** Separate pref keys so existing installs keep working unchanged: the device token and the
     *  Auth envelope (issued only by the sadad-auth gateway) are stored independently. */
    private static final String AUTH_KEY = "auth_envelope";
    private final SharedPreferences preferences;

    SessionStore(Context context) { this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)); }
    SessionStore(SharedPreferences preferences) { this.preferences = preferences; }

    synchronized void save(JSONObject response) throws Exception {
        JSONObject account = response.optJSONObject("account");
        String accountId = account == null ? "" : account.optString("id", "");
        if (!accountId.isEmpty() && !canSaveAccount(accountId)) throw new IllegalStateException("يمكن حفظ ثلاثة حسابات متاجر كحد أقصى على هذا الجهاز.");
        String encryptedToken = encryptToken(response.getString("token"));
        JSONObject auth = response.optJSONObject("auth");
        String encryptedAuth = auth == null ? "" : encryptToken(auth.toString());
        JSONObject device = response.optJSONObject("device");
        String staffName = response.optString("deviceStaffName", device == null ? "" : device.optString("staffName", ""));
        JSONObject devicePermissions = device == null ? new JSONObject() : device.optJSONObject("permissions");
        SharedPreferences.Editor editor = preferences.edit()
                .putString(AUTH_KEY, encryptedAuth)
                .putString("token", encryptedToken)
                .putLong("expires_at", response.optLong("expiresAt", 0L))
                .putString("account", account == null ? "{}" : account.toString())
                .putLong("revision", response.optJSONObject("snapshot") == null ? 0L : response.optJSONObject("snapshot").optLong("revision", 0L))
                .putBoolean("force_password_change", response.optBoolean("forcePasswordChange", false))
                .putString("device_staff_name", staffName)
                .putString("device_permissions", devicePermissions == null ? "{}" : devicePermissions.toString());
        if (account != null) editor.putString("sync_owner", account.optString("username", "")).putString("sync_account_id", accountId);
        if (account != null && !accountId.isEmpty()) {
            JSONObject saved = new JSONObject().put("id", accountId).put("username", account.optString("username", ""))
                    .put("name", account.optString("name", "")).put("account", account.toString()).put("encryptedToken", encryptedToken)
                    .put("expiresAt", response.optLong("expiresAt", 0L)).put("revision", response.optJSONObject("snapshot") == null ? 0L : response.optJSONObject("snapshot").optLong("revision", 0L))
                    .put("staffName", staffName).put("devicePermissions", devicePermissions == null ? "{}" : devicePermissions.toString())
                    .put("forcePasswordChange", response.optBoolean("forcePasswordChange", false))
                    .put("encryptedAuth", encryptedAuth);
            editor.putString("saved_accounts", mergedSavedAccounts(saved).toString());
        }
        if (!editor.commit()) throw new IllegalStateException("تعذر حفظ الجلسة.");
    }

    public synchronized String token() {
        try { return decryptToken(preferences.getString("token", "")); }
        catch (Exception e) { return ""; }
    }

    synchronized JSONArray savedAccounts() {
        try { return new JSONArray(preferences.getString("saved_accounts", "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    synchronized void ensureCurrentAccountSaved() { updateCurrentSavedBundle(); }

    synchronized boolean canSaveAccount(String accountId) {
        JSONArray saved = savedAccounts();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item != null && accountId.equals(item.optString("id"))) return true;
        }
        return saved.length() < 3;
    }

    synchronized boolean hasSavedUsername(String username) {
        JSONArray saved = savedAccounts();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item != null && username.equalsIgnoreCase(item.optString("username", ""))) return true;
        }
        return false;
    }

    synchronized boolean activateSavedAccount(String accountId) {
        JSONArray saved = savedAccounts();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null || !accountId.equals(item.optString("id"))) continue;
            try {
                String encryptedToken = item.optString("encryptedToken", "");
                if (decryptToken(encryptedToken).isEmpty()) return false;
                JSONObject account = new JSONObject(item.optString("account", "{}"));
                preferences.edit().putString("token", encryptedToken).putLong("expires_at", item.optLong("expiresAt", 0L))
                        .putString("account", account.toString()).putLong("revision", item.optLong("revision", 0L))
                        .putBoolean("force_password_change", item.optBoolean("forcePasswordChange", false)).putString("sync_owner", item.optString("username", ""))
                        .putString("sync_account_id", accountId).putBoolean("sync_dirty", false).putBoolean("sync_blocked", false)
                        .putString("device_staff_name", item.optString("staffName", ""))
                        .putString("device_permissions", item.optString("devicePermissions", "{}"))
                        .putString(AUTH_KEY, item.optString("encryptedAuth", ""))
                        .apply();
                return true;
            } catch (Exception e) { return false; }
        }
        return false;
    }

    synchronized boolean activateFirstSavedAccount() {
        JSONArray saved = savedAccounts();
        JSONObject first = saved.optJSONObject(0);
        return first != null && activateSavedAccount(first.optString("id", ""));
    }

    synchronized void removeSavedAccount(String accountId) {
        JSONArray saved = savedAccounts(), updated = new JSONArray();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item != null && !accountId.equals(item.optString("id"))) updated.put(item);
        }
        preferences.edit().putString("saved_accounts", updated.toString()).apply();
    }

    synchronized String databaseNameForAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) return "sadad.db";
        String primary = preferences.getString("primary_database_account", "");
        if (primary.isEmpty()) { preferences.edit().putString("primary_database_account", accountId).apply(); return "sadad.db"; }
        if (accountId.equals(primary)) return "sadad.db";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("sadad-account-");
            for (int i = 0; i < 12; i++) name.append(String.format(java.util.Locale.US, "%02x", digest[i]));
            return name.append(".db").toString();
        } catch (Exception e) { return "sadad-account-" + Integer.toHexString(accountId.hashCode()) + ".db"; }
    }

    String deviceStaffName() { return preferences.getString("device_staff_name", ""); }
    JSONObject devicePermissions() {
        try { return new JSONObject(preferences.getString("device_permissions", "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }

    private synchronized JSONArray mergedSavedAccounts(JSONObject account) throws JSONException {
        JSONArray saved = savedAccounts(), updated = new JSONArray(); boolean found = false;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i); if (item == null) continue;
            if (account.optString("id").equals(item.optString("id"))) { updated.put(account); found = true; }
            else updated.put(item);
        }
        if (!found) updated.put(account);
        return updated;
    }

    private synchronized void upsertSavedAccount(JSONObject account) throws JSONException {
        preferences.edit().putString("saved_accounts", mergedSavedAccounts(account).toString()).apply();
    }

    private String encryptToken(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), iv = cipher.getIV(), result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length); System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    private String decryptToken(String encoded) throws Exception {
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP); if (bytes.length < 29) return "";
        byte[] iv = Arrays.copyOfRange(bytes, 0, 12), encrypted = Arrays.copyOfRange(bytes, 12, bytes.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public boolean isActive() {
        String currentToken = token();
        if (currentToken.isEmpty() || expiresAt() <= System.currentTimeMillis()) return false;
        if (currentToken.startsWith("offline-")) return BuildConfig.STANDALONE_MODE;
        JSONObject current = account();
        String mode = current.optString("subscriptionMode", "permanent");
        long subscriptionExpiry = current.optLong("subscriptionExpiresAt", 0L);
        return !"paused".equals(mode) && (!"timed".equals(mode) || subscriptionExpiry > System.currentTimeMillis());
    }
    long expiresAt() { return preferences.getLong("expires_at", 0L); }
    public long revision() { return preferences.getLong("revision", 0L); }
    public void setRevision(long revision) { preferences.edit().putLong("revision", revision).apply(); updateCurrentSavedBundle(); }
    public String deviceId() {
        String value = preferences.getString("device_id", "");
        if (!value.isEmpty()) return value;
        value = UUID.randomUUID().toString();
        preferences.edit().putString("device_id", value).apply();
        return value;
    }
    public boolean hasUnsyncedChanges() { return preferences.getBoolean("sync_dirty", false); }
    public void setUnsyncedChanges(boolean value) { preferences.edit().putBoolean("sync_dirty", value).apply(); }
    public boolean syncBlocked() { return preferences.getBoolean("sync_blocked", false); }
    public void setSyncBlocked(boolean value) { preferences.edit().putBoolean("sync_blocked", value).apply(); }
    String syncOwner() { return preferences.getString("sync_owner", ""); }
    public String syncAccountId() { return preferences.getString("sync_account_id", ""); }
    public boolean forcePasswordChange() { return preferences.getBoolean("force_password_change", false); }
    public void setForcePasswordChange(boolean value) { preferences.edit().putBoolean("force_password_change", value).apply(); updateCurrentSavedBundle(); }
    public JSONObject account() {
        try { return new JSONObject(preferences.getString("account", "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    public void setAccount(JSONObject account) { preferences.edit().putString("account", account == null ? "{}" : account.toString()).apply(); updateCurrentSavedBundle(); }
    // Upgraded devices may still contain an editable origin; never send their token there.
    public String apiBaseUrl() { return BuildConfig.API_BASE_URL; }

    /** The gateway that issues the Auth envelope. Fixed at build time, like the ledger's own origin:
     *  a device session must not be renewable against a host the user can type into. */
    public String authBaseUrl() { return BuildConfig.AUTH_BASE_URL; }

    /** Auth envelope (accessToken/refreshToken/expiresAt) issued by the sadad-auth gateway.
     *  Empty when the account has never logged in through the gateway. */
    public synchronized JSONObject auth() {
        try { return new JSONObject(decryptToken(preferences.getString(AUTH_KEY, ""))); }
        catch (Exception e) { return new JSONObject(); }
    }

    public synchronized void updateAuth(JSONObject auth) throws Exception {
        if (auth == null) return;
        String encrypted = encryptToken(auth.toString());
        JSONArray saved = savedAccounts();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject account = saved.optJSONObject(i);
            if (account != null && syncAccountId().equals(account.optString("id"))) account.put("encryptedAuth", encrypted);
        }
        if (!preferences.edit().putString(AUTH_KEY, encrypted).putString("saved_accounts", saved.toString()).commit())
            throw new IllegalStateException("تعذر حفظ الجلسة المجددة.");
    }

    /** The ledger is always the signed one for a connected build; nothing negotiates a mode. */

    void clear() { preferences.edit().remove("token").remove("expires_at").remove("account").remove("force_password_change").remove(AUTH_KEY).apply(); }

    synchronized boolean devicePermissionAllowed(String key) { return devicePermissions().optBoolean(key, true); }

    private synchronized void updateCurrentSavedBundle() {
        String id = syncAccountId();
        if (id.isEmpty() || token().isEmpty()) return;
        try {
            JSONObject account = account();
            JSONObject saved = new JSONObject().put("id", id).put("username", account.optString("username", syncOwner()))
                    .put("name", account.optString("name", "")).put("account", account.toString())
                    .put("encryptedToken", preferences.getString("token", "")).put("expiresAt", expiresAt())
                    .put("revision", revision()).put("staffName", deviceStaffName())
                    .put("devicePermissions", devicePermissions().toString()).put("forcePasswordChange", forcePasswordChange())
                    .put("encryptedAuth", preferences.getString(AUTH_KEY, ""));
            upsertSavedAccount(saved);
        } catch (Exception ignored) { }
    }

    private SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore"); keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
