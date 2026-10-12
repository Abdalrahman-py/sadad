package com.sadad.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InstallationKeyTest {
    @Test public void twoStoresHaveDistinctStableIdentitiesAfterReopening() throws Exception {
        TestPreferences storage = new TestPreferences();
        String first = new InstallationKey(storage.open(), "7").id();
        String second = new InstallationKey(storage.open(), "3").id();
        assertNotEquals(first, second);
        assertEquals(first, new InstallationKey(storage.open(), "7").id());
        assertEquals(second, new InstallationKey(storage.open(), "3").id());
    }

    @Test public void aGlobalTestInstallationIsNeverAdoptedByAStore() throws Exception {
        TestPreferences storage = new TestPreferences();
        storage.values.put("id", "legacy-installation");
        assertNotEquals("legacy-installation", new InstallationKey(storage.open(), "7").id());
        assertNotEquals("legacy-installation", new InstallationKey(storage.open(), "3").id());
        assertNotEquals("legacy-installation", new InstallationKey(storage.open(), "7").id());
    }
}
