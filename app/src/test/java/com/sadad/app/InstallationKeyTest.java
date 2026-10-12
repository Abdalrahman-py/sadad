package com.sadad.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InstallationKeyTest {
    @Test public void twoStoresHaveDistinctStableIdentitiesAfterReopening() throws Exception {
        TestPreferences storage = new TestPreferences();
        String first = new InstallationKey(storage.open(), "7", false).id();
        String second = new InstallationKey(storage.open(), "3", false).id();
        assertNotEquals(first, second);
        assertEquals(first, new InstallationKey(storage.open(), "7", false).id());
        assertEquals(second, new InstallationKey(storage.open(), "3", false).id());
    }

    @Test public void theVerifiedOriginalStoreKeepsItsLegacyIdentity() throws Exception {
        TestPreferences storage = new TestPreferences();
        storage.values.put("id", "legacy-installation");
        assertEquals("legacy-installation", new InstallationKey(storage.open(), "7", true).id());
        assertNotEquals("legacy-installation", new InstallationKey(storage.open(), "3", true).id());
        assertEquals("legacy-installation", new InstallationKey(storage.open(), "7", false).id());
    }
}
