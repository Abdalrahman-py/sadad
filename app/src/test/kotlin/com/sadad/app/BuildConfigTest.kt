package com.sadad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
  @Test
  fun targetsTheUnifiedApi() {
    assertTrue(BuildConfig.API_BASE_URL.endsWith("/functions/v1/api"))
    assertFalse(BuildConfig.API_BASE_URL.contains("sadad-"))
  }

  @Test
  fun embedsOnlyAPublishableKey() {
    assertTrue(BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_"))
  }

  @Test
  fun appIdentityMatchesBuildType() {
    val expected = if (BuildConfig.DEBUG) "com.sadad.app.debug" else "com.sadad.app"
    assertEquals(expected, BuildConfig.APPLICATION_ID)
  }
}
