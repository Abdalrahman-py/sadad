package com.sadad.app;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/** The device facts the lease rules read, on the device. */
final class AndroidEnvironment implements DeviceEnvironment {
    private final Context context;

    AndroidEnvironment(Context context) { this.context = context.getApplicationContext(); }

    @Override public long bootCount() {
        try { return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
        catch (Exception unavailable) { return -1L; }
    }

    @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }

    @Override public long wallClock() { return System.currentTimeMillis(); }
}
