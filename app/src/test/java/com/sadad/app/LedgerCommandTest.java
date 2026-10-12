package com.sadad.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The command shape the signed ledger validates, and the pieces of it that are easy to get wrong. */
public class LedgerCommandTest {
    @Test public void aCommandCarriesTheEpochAndTheInstallationThatMadeIt() throws Exception {
        JSONObject command = LedgerCommand.of(LedgerCommand.DEBT_CREATE, "entity-1",
                LedgerCommand.debtPayload("contact-1", 1250L, "receivable", " دين ", ""),
                "installation-1", 4, "epoch-1", null, "2026-10-12T10:00:00.000+03:00");
        assertEquals(1, command.getInt("schemaVersion"));
        assertEquals(LedgerCommand.DEBT_CREATE, command.getString("type"));
        assertEquals("entity-1", command.getString("entityId"));
        assertEquals("installation-1", command.getString("originInstallationId"));
        assertEquals(4, command.getInt("originGeneration"));
        assertEquals("epoch-1", command.getString("expectedEpoch"));
        assertEquals("2026-10-12T10:00:00.000+03:00", command.getString("createdAt"));
        assertEquals(0, command.getJSONArray("dependsOn").length());
        // The ledger matches a resend against the exact bytes, so the operation id has to be a UUID.
        assertEquals(36, command.getString("operationId").length());
        assertEquals(command.getString("operationId"), command.getString("operationId"));
        assertFalse(command.getString("operationId").equals(command.getString("entityId")));
    }

    @Test public void aFreshEntityAndOperationIdAreNeverTheSame() {
        assertFalse(LedgerCommand.newEntityId().equals(LedgerCommand.newEntityId()));
    }

    @Test public void theTimestampIsTheShapeTheLedgerParsesBack() {
        String now = LedgerCommand.now(1_761_000_000_000L);
        assertTrue(now, now.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2}$"));
        assertEquals(1_761_000_000_000L, LedgerProjection.millisOf(now));
    }

    @Test public void theContactPayloadIsTrimmedAndKeepsThePhoneAsTyped() throws Exception {
        JSONObject payload = LedgerCommand.contactPayload("  أبو أحمد ", " +970599123456 ", " عميل ", "  ملاحظة ");
        assertEquals("أبو أحمد", payload.getString("name"));
        assertEquals("+970599123456", payload.getString("phone"));
        assertEquals("عميل", payload.getString("category"));
        assertEquals("ملاحظة", payload.getString("note"));
    }

    @Test public void aDebtWithoutADueDateSendsNoDateFieldAtAll() throws Exception {
        assertFalse(LedgerCommand.debtPayload("c", 100L, "receivable", "", "").has("dueDate"));
        assertFalse(LedgerCommand.debtPayload("c", 100L, "receivable", "", "   ").has("dueDate"));
        assertEquals("2026-11-01", LedgerCommand.debtPayload("c", 100L, "receivable", "", " 2026-11-01 ").getString("dueDate"));
        assertEquals("receivable", LedgerCommand.debtPayload("c", 100L, null, "", "").getString("direction"));
    }

    @Test public void everyPaymentMethodTheAppOffersSurvivesAndAnythingElseBecomesCash() throws Exception {
        for (String method : new String[]{"cash", "bank", "wallet", "wallet_jawwal", "wallet_palpay"}) {
            assertEquals(method, LedgerCommand.paymentPayload("d", 100L, method, "").getString("method"));
        }
        assertEquals("cash", LedgerCommand.paymentPayload("d", 100L, "bitcoin", "").getString("method"));
        assertEquals("cash", LedgerCommand.paymentPayload("d", 100L, null, "").getString("method"));
    }

    @Test public void onlyTheThreeOfflineTypesMayBeQueued() {
        assertTrue(LedgerCommand.offlineType(LedgerCommand.CONTACT_CREATE));
        assertTrue(LedgerCommand.offlineType(LedgerCommand.DEBT_CREATE));
        assertTrue(LedgerCommand.offlineType(LedgerCommand.PAYMENT_CREATE));
        assertFalse(LedgerCommand.offlineType("contact.archive"));
        assertFalse(LedgerCommand.offlineType("debt.correct"));
    }

    @Test public void dependenciesAreSentAsAnArrayOfOperationIds() throws Exception {
        JSONArray dependencies = new JSONArray().put("op-1").put("op-2");
        JSONObject command = LedgerCommand.of(LedgerCommand.PAYMENT_CREATE, "entity", new JSONObject(), "i", 1, "e", dependencies, LedgerCommand.now());
        assertEquals(2, command.getJSONArray("dependsOn").length());
        assertEquals("op-1", command.getJSONArray("dependsOn").getString(0));
    }
}
