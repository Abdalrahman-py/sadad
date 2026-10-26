package com.sadad.app.feature.quickadd

import com.sadad.ledger.Cents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind the line under the amount. It is the one place where a debt and a payment
 * move the balance in opposite directions, and where a debt may first eat a credit the person
 * already holds — so it is asserted here, away from the screen and the store.
 */
class PreviewWordsTest {
  @Test
  fun aCustomerDebtAddsToWhatTheyOwe() {
    val words = previewWords(Cents(10_000), Cents(2_500), payment = false, supplier = false)

    assertEquals(Cents(12_500), words.after)
    assertNull("nothing is deducted when they already owe", words.deduct)
  }

  @Test
  fun aCustomerDebtFirstEatsTheCreditTheyHold() {
    val partly = previewWords(Cents(-10_000), Cents(2_500), payment = false, supplier = false)
    assertEquals("the whole debt comes out of the credit", Cents(2_500), partly.deduct)
    assertEquals(Cents(-7_500), partly.after)

    val wholly = previewWords(Cents(-1_000), Cents(2_500), payment = false, supplier = false)
    assertEquals("only the credit they hold is deducted", Cents(1_000), wholly.deduct)
    assertEquals(Cents(1_500), wholly.after)
  }

  @Test
  fun aCustomerPaymentSubtractsFromWhatTheyOwe() {
    val words = previewWords(Cents(10_000), Cents(4_000), payment = true, supplier = false)

    assertEquals(Cents(6_000), words.after)
    assertNull(words.deduct)
  }

  @Test
  fun aSupplierDebtIsWhatTheStoreOwesAndItsPaymentReducesIt() {
    val debt = previewWords(Cents(-10_000), Cents(3_000), payment = false, supplier = true)
    assertEquals("goods bought on credit deepen what the store owes", Cents(-13_000), debt.after)
    assertNull("a supplier debt never deducts a credit", debt.deduct)

    val paid = previewWords(Cents(-10_000), Cents(3_000), payment = true, supplier = true)
    assertEquals(Cents(-7_000), paid.after)
  }

  @Test
  fun theDoneLineDropsTheVerbTheTitleAlreadySays() {
    assertEquals("₪50 على خالد", withoutVerb("سجّل ₪50 على خالد"))
    assertEquals("دفعة ₪50 من خالد", withoutVerb("سجّل دفعة ₪50 من خالد"))
    assertEquals("₪300 لمخبز الأمل", withoutVerb("سجّل ₪300 لمخبز الأمل"))
    assertEquals("مسدد", withoutVerb("مسدد"))
  }

  @Test
  fun aPaymentThatSettlesTheBalanceExactlyEndsAtZero() {
    val words = previewWords(Cents(10_000), Cents(10_000), payment = true, supplier = false)

    assertEquals(Cents.ZERO, words.after)
  }
}
