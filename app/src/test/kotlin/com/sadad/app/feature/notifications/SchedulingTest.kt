package com.sadad.app.feature.notifications

import java.time.Duration
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulingTest {
  @Test fun schedulesTheNextLocalNineOClock() {
    assertEquals(Duration.ofHours(1), reminderInitialDelay(ZonedDateTime.parse("2026-10-17T08:00:00+03:00[Asia/Gaza]")))
    assertEquals(Duration.ofHours(23), reminderInitialDelay(ZonedDateTime.parse("2026-10-17T10:00:00+03:00[Asia/Gaza]")))
  }
}
