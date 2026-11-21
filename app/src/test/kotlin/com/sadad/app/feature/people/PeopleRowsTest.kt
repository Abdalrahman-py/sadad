package com.sadad.app.feature.people

import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.Origin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list's rows at the seam where they are built: one summary list plus the outbox rows, no
 * person in it twice while a rejected contact settles (the ticket-215 race).
 */
class PeopleRowsTest {
  private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
  private val today: LocalDate = LocalDate.of(2026, 10, 25)

  @Test
  fun aPersonInTheSummaryWithARejectedCreateGivesExactlyOneRow() {
    val id = "c-1"
    val at = at(10, 24)
    val summaries = listOf(summary(id, "أحمد سالم", at))

    val rows =
        summaryRowsFrom(
            summaries,
            listOf(rejectedCreate(id, "أحمد سالم", at.plusSeconds(3_600))),
            zone,
            today,
        )

    assertEquals("one row for the person, from the summary", 1, rows.count { it.id == id })
    assertEquals("أحمد سالم", rows.single { it.id == id }.name)
  }

  @Test
  fun aRejectedCreateUnknownToTheSummaryStaysAsOneFailedDraftRow() {
    val id = "c-2"

    val rows =
        summaryRowsFrom(emptyList(), listOf(rejectedCreate(id, "شخص جديد", at(12, 0))), zone, today)

    val row = rows.single { it.id == id }
    assertTrue(row.syncFailed)
    assertEquals(Cents.ZERO, row.net)
  }

  private fun summary(contactId: String, name: String, createdAt: Instant): ContactSummaryEntity =
      ContactSummaryEntity(
          contactId = contactId,
          name = name,
          phone = "0598440905",
          kind = ContactKind.CUSTOMER,
          category = "",
          pending = false,
          createdAt = createdAt,
          net = 0L,
          openReceivable = 0L,
          openPayable = 0L,
          earliestOpenDue = null,
          lastEntryAt = null,
          searchKey = name,
      )

  private fun rejectedCreate(entityId: String, name: String, at: Instant): OutboxRow {
    val command =
        Commands.contactCreate(
            Origin("installation-1", 1, "epoch-1"),
            at,
            name,
            phone = "0598440905",
            entityId = entityId,
            operationId = "op-$entityId",
        )
    return OutboxRow(
        seq = 1,
        operationId = command.operationId,
        entityId = command.entityId,
        type = command.type,
        state = OutboxState.REJECTED,
        code = "contact_limit_reached",
        createdAt = at,
        recoveryPermit = null,
        command = command,
    )
  }

  private fun at(hour: Int, minute: Int): Instant =
      today.atTime(hour, minute).atZone(zone).toInstant()
}
