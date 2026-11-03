package com.sadad.app.feature.contactpicker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.ui.UiTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The picker may add exactly one permission: READ_CONTACTS. Nothing else may arrive with it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactPickerManifestTest {
  @Test
  fun thePickerAsksOnlyForReadingContacts() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val asked =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

    assertTrue(
        "READ_CONTACTS is missing from the merged manifest",
        Manifest.permission.READ_CONTACTS in asked,
    )
    val expected =
        setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.USE_BIOMETRIC",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            Manifest.permission.READ_CONTACTS,
            // below: what the libraries the app already ships merge in, not this ticket's doing
            "android.permission.USE_FINGERPRINT",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
            context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    assertEquals("the picker added a permission it did not need", expected, asked)
  }
}
