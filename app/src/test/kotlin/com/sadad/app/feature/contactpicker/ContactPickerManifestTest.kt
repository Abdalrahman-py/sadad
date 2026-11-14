package com.sadad.app.feature.contactpicker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.ui.UiTestApplication
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contacts come through Android's own picker, which grants the one row picked: the app must not ask
 * for READ_CONTACTS (Play treats the address book as sensitive data).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactPickerManifestTest {
  @Test
  fun theAppAsksForNoContactsPermission() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val asked =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

    assertFalse(
        "READ_CONTACTS is back in the merged manifest",
        Manifest.permission.READ_CONTACTS in asked,
    )
  }
}
