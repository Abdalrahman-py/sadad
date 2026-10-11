package com.sadad.app;

/**
 * The device facts the lease rules depend on. Injected rather than read from Android, so the rules
 * that decide whether a store may add entries offline can be tested off-device — they are the rules
 * a wrong answer loses money on.
 */
interface DeviceEnvironment {
    /** Boot count, or -1 when the device cannot report it. A lease does not survive a reboot. */
    long bootCount();

    /** Milliseconds since boot. Immune to the user moving the clock. */
    long elapsedRealtime();

    /** Milliseconds since the epoch. */
    long wallClock();
}
