package com.sadad.ledger

import com.sadad.ledger.Events.accepted
import com.sadad.ledger.Events.at
import com.sadad.ledger.Events.origin
import com.sadad.ledger.Events.page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingSetTest {
  private fun id(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

  @Test
  fun contactCreateNamesOnlyItsEntity() {
    val c = id(1)
    val cmd = Commands.contactCreate(origin, at(0), "أ", entityId = c)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(c), ids.contacts)
    assertTrue(ids.debts.isEmpty())
    assertTrue(ids.payments.isEmpty())
    assertTrue(ids.offsets.isEmpty())
    assertTrue(ids.withAllRows.isEmpty())
  }

  @Test
  fun contactUpdateNamesEntityAndContact() {
    val c = id(1)
    val op = id(101)
    val cmd = Commands.contactUpdate(origin, at(0), c, 1, "أ", entityId = op)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(op, c), ids.contacts)
    assertTrue(ids.debts.isEmpty())
    assertTrue(ids.payments.isEmpty())
    assertTrue(ids.offsets.isEmpty())
  }

  @Test
  fun contactArchiveAndRestoreNameContact() {
    val c = id(1)
    val opA = id(102)
    val opR = id(103)
    val archive = Commands.contactArchive(origin, at(0), c, 1, "سلة", entityId = opA)
    val restore = Commands.contactRestore(origin, at(1), c, 2, "استعادة", entityId = opR)
    val archiveIds = page(listOf(accepted(archive, 1))).workingSetIds()
    assertEquals(setOf(opA, c), archiveIds.contacts)
    val restoreIds = page(listOf(accepted(restore, 1))).workingSetIds()
    assertEquals(setOf(opR, c), restoreIds.contacts)
  }

  @Test
  fun contactDeleteFlagsWithAllRows() {
    val c = id(1)
    val cmd = Commands.contactDelete(origin, at(0), c, 2, "نهائي", entityId = c)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(c), ids.contacts)
    assertEquals(setOf(c), ids.withAllRows)
    assertTrue(ids.debts.isEmpty())
  }

  @Test
  fun debtCreateNamesDebtAndContact() {
    val c = id(1)
    val d = id(11)
    val cmd = Commands.debtCreate(origin, at(0), c, Direction.RECEIVABLE, Cents(1000), entityId = d)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(c), ids.contacts)
    assertEquals(setOf(d), ids.debts)
    assertTrue(ids.payments.isEmpty())
    assertTrue(ids.offsets.isEmpty())
  }

  @Test
  fun debtCorrectNamesEntityAndDebt() {
    val d = id(11)
    val op = id(105)
    val cmd = Commands.debtCorrect(origin, at(0), d, 1, Cents(2000), "تصحيح", entityId = op)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(op, d), ids.debts)
    assertTrue(ids.contacts.isEmpty())
  }

  @Test
  fun debtOffsetNamesOffsetAndBothDebts() {
    val d1 = id(11)
    val d2 = id(12)
    val o = id(31)
    val cmd = Commands.debtOffset(origin, at(0), d1, d2, Cents(500), entityId = o)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(o), ids.offsets)
    assertEquals(setOf(d1, d2), ids.debts)
  }

  @Test
  fun paymentCreateNamesPaymentAndDebt() {
    val d = id(11)
    val p = id(21)
    val cmd = Commands.paymentCreate(origin, at(0), d, Cents(500), PaymentMethod.CASH, entityId = p)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(p), ids.payments)
    assertEquals(setOf(d), ids.debts)
  }

  @Test
  fun paymentReverseNamesEntityAndPayment() {
    val p = id(21)
    val op = id(106)
    val cmd = Commands.paymentReverse(origin, at(0), p, "خطأ", entityId = op)
    val ids = page(listOf(accepted(cmd, 1))).workingSetIds()
    assertEquals(setOf(op, p), ids.payments)
  }

  @Test
  fun rejectedEventsAddNothing() {
    val c = id(1)
    val create = Commands.contactCreate(origin, at(0), "أ", entityId = c)
    val json =
        LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), create)
            as kotlinx.serialization.json.JsonObject
    val rejected = Events.rejected(json, 1)
    val ids = page(listOf(rejected)).workingSetIds()
    assertTrue(ids.contacts.isEmpty())
    assertTrue(ids.debts.isEmpty())
    assertTrue(ids.payments.isEmpty())
    assertTrue(ids.offsets.isEmpty())
    assertTrue(ids.withAllRows.isEmpty())
  }

  @Test
  fun workingSetFoldEqualsFullFoldForThreePages() {
    val cA = id(1)
    val cB = id(2)
    val dA1 = id(11)
    val dA2 = id(12)
    val dB1 = id(13)
    val oA = id(31)
    val pA1 = id(21)
    val pB1 = id(22)
    val pA2 = id(23)
    val dB2 = id(14)

    val p1 =
        page(
            listOf(
                accepted(Commands.contactCreate(origin, at(0), "أ", entityId = cA), 1),
                accepted(Commands.contactCreate(origin, at(1), "ب", entityId = cB), 2),
                accepted(
                    Commands.debtCreate(
                        origin,
                        at(2),
                        cA,
                        Direction.RECEIVABLE,
                        Cents(10_000),
                        entityId = dA1,
                    ),
                    3,
                ),
                accepted(
                    Commands.debtCreate(
                        origin,
                        at(3),
                        cA,
                        Direction.PAYABLE,
                        Cents(3_000),
                        entityId = dA2,
                    ),
                    4,
                ),
                accepted(
                    Commands.debtCreate(
                        origin,
                        at(4),
                        cB,
                        Direction.RECEIVABLE,
                        Cents(8_000),
                        entityId = dB1,
                    ),
                    5,
                ),
                accepted(
                    Commands.debtOffset(origin, at(5), dA1, dA2, Cents(1_000), entityId = oA),
                    6,
                ),
                accepted(
                    Commands.paymentCreate(
                        origin,
                        at(6),
                        dA1,
                        Cents(2_000),
                        PaymentMethod.CASH,
                        entityId = pA1,
                    ),
                    7,
                ),
                accepted(
                    Commands.paymentCreate(
                        origin,
                        at(7),
                        dB1,
                        Cents(1_000),
                        PaymentMethod.CASH,
                        entityId = pB1,
                    ),
                    8,
                ),
            )
        )
    val p2 =
        page(
            listOf(
                accepted(
                    Commands.contactUpdate(origin, at(8), cB, 1, "ب جديد", entityId = cB),
                    9,
                ),
                accepted(
                    Commands.debtCorrect(
                        origin,
                        at(9),
                        dB1,
                        1,
                        Cents(9_000),
                        "تصحيح",
                        entityId = id(107),
                    ),
                    10,
                ),
                accepted(
                    Commands.paymentReverse(origin, at(10), pB1, "خطأ", entityId = id(108)),
                    11,
                ),
                accepted(
                    Commands.paymentCreate(
                        origin,
                        at(11),
                        dA1,
                        Cents(1_000),
                        PaymentMethod.CASH,
                        entityId = pA2,
                    ),
                    12,
                ),
                accepted(
                    Commands.contactArchive(origin, at(12), cB, 2, "سلة", entityId = cB),
                    13,
                ),
                accepted(
                    Commands.contactRestore(origin, at(13), cB, 3, "استعادة", entityId = cB),
                    14,
                ),
                accepted(
                    Commands.contactArchive(origin, at(14), cA, 1, "سلة", entityId = cA),
                    15,
                ),
                accepted(
                    Commands.debtCreate(
                        origin,
                        at(15),
                        cB,
                        Direction.RECEIVABLE,
                        Cents(500),
                        entityId = dB2,
                    ),
                    16,
                ),
            )
        )
    val p3 =
        page(
            listOf(
                accepted(
                    Commands.contactDelete(origin, at(16), cA, 2, "نهائي", entityId = cA),
                    17,
                ),
            )
        )

    var full = LedgerState.EMPTY
    var merged = LedgerState.EMPTY
    for (pg in listOf(p1, p2, p3)) {
      val wsIds = pg.workingSetIds()
      val ws = filterWorkingSet(full, wsIds)
      val nextWs = ws.apply(pg)
      merged = merge(full, ws, nextWs)
      full = full.apply(pg)
      assertEquals(full, merged)
    }
    // Delete removed A with its debts, payments and offsets; B remains.
    assertEquals(setOf(cB), full.contacts.keys)
    assertEquals(setOf(dB1, dB2), full.debts.keys)
    assertEquals(setOf(pB1), full.payments.keys)
    assertTrue(full.offsets.isEmpty())
  }

  private fun filterWorkingSet(full: LedgerState, ids: WorkingSetIds): LedgerState {
    // Mirror LedgerStore.loadWorkingSet expansions on the in-memory full state.
    val withAllDebtIds = mutableSetOf<String>()
    for (cid in ids.withAllRows) {
      for ((did, debt) in full.debts) if (debt.contactId == cid) withAllDebtIds += did
    }
    val debtIds = (ids.debts + withAllDebtIds).toMutableSet()
    // Payment -> debt expansion for payments named by the page.
    for (pid in ids.payments) full.payments[pid]?.let { debtIds += it.debtId }
    // With-all-rows payments and offsets.
    val paymentIds = ids.payments.toMutableSet()
    val offsetIds = ids.offsets.toMutableSet()
    if (withAllDebtIds.isNotEmpty()) {
      for ((pid, pay) in full.payments) if (pay.debtId in withAllDebtIds) paymentIds += pid
      for ((oid, off) in full.offsets) if (
          off.receivableDebtId in withAllDebtIds || off.payableDebtId in withAllDebtIds
      )
          offsetIds += oid
    }
    // Debt -> contact expansion.
    val contactIds = (ids.contacts + ids.withAllRows).toMutableSet()
    for (did in debtIds) full.debts[did]?.let { contactIds += it.contactId }

    val contacts = LinkedHashMap<String, ContactRow>()
    for ((k, v) in full.contacts) if (k in contactIds) contacts[k] = v
    val debts = LinkedHashMap<String, DebtRow>()
    for ((k, v) in full.debts) if (k in debtIds) debts[k] = v
    val payments = LinkedHashMap<String, PaymentRow>()
    for ((k, v) in full.payments) if (k in paymentIds) payments[k] = v
    val offsets = LinkedHashMap<String, OffsetRow>()
    for ((k, v) in full.offsets) if (k in offsetIds) offsets[k] = v
    return LedgerState(full.storeId, full.epoch, full.cursor, contacts, debts, payments, offsets)
  }

  private fun merge(full: LedgerState, ws: LedgerState, next: LedgerState): LedgerState {
    val contacts = LinkedHashMap(full.contacts)
    for ((k, v) in next.contacts) contacts[k] = v
    for (k in ws.contacts.keys - next.contacts.keys) contacts.remove(k)
    val debts = LinkedHashMap(full.debts)
    for ((k, v) in next.debts) debts[k] = v
    for (k in ws.debts.keys - next.debts.keys) debts.remove(k)
    val payments = LinkedHashMap(full.payments)
    for ((k, v) in next.payments) payments[k] = v
    for (k in ws.payments.keys - next.payments.keys) payments.remove(k)
    val offsets = LinkedHashMap(full.offsets)
    for ((k, v) in next.offsets) offsets[k] = v
    for (k in ws.offsets.keys - next.offsets.keys) offsets.remove(k)
    return LedgerState(next.storeId, next.epoch, next.cursor, contacts, debts, payments, offsets)
  }
}
