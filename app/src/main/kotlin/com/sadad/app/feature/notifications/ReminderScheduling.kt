package com.sadad.app.feature.notifications

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** Use the next local calendar day, so a zone offset change keeps the target at 09:00. */
fun reminderInitialDelay(now: ZonedDateTime): Duration {
  var next = now.toLocalDate().atTime(LocalTime.of(9, 0)).atZone(now.zone)
  if (!next.isAfter(now)) next = next.plusDays(1)
  return Duration.between(now, next)
}
