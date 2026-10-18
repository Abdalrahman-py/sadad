package com.sadad.app.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

/** `dd/MM/yyyy` with Latin digits, in the device time zone. */
fun formatDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_FORMAT.withZone(zone).format(instant)

/** The `yyyy-MM-dd` text the wire and the forms exchange, or null when it is not a valid date. */
fun parseDueDate(text: String): LocalDate? =
    try {
      LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (invalid: DateTimeParseException) {
      null
    }

fun formatDueDate(date: LocalDate): String = date.toString()
