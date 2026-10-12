package com.sadad.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The projection: what the store sees when the server's ledger and this device's queue are folded
 * together. This is where the money arithmetic lives, so it is exercised without a device.
 */
public class LedgerProjectionTest {
    private static final String SELF = "installation-self";

    private static long utc(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    // ------------------------------------------------------------------ timestamps

    @Test public void aPostgresTimestampBecomesMilliseconds() {
        long expected = utc(2026, 10, 10, 6, 0);
        assertEquals(expected, LedgerProjection.millisOf("2026-10-10T06:00:00+00:00"));
        assertEquals(expected, LedgerProjection.millisOf("2026-10-10T06:00:00Z"));
        // A fractional part is an instant too; only the first three digits matter at millisecond scale.
        assertEquals(expected, LedgerProjection.millisOf("2026-10-10T06:00:00.000000+00:00"));
        assertEquals(expected + 123L, LedgerProjection.millisOf("2026-10-10T06:00:00.123456+00:00"));
        // An offset is part of the instant, not decoration: 09:00+03:00 is the same moment as 06:00Z.
        assertEquals(expected, LedgerProjection.millisOf("2026-10-10T09:00:00+03:00"));
        assertEquals(expected, LedgerProjection.millisOf("2026-10-10T01:30:00-04:30"));
        assertEquals(expected + 123L, LedgerProjection.millisOf("2026-10-10T06:00:00.123+00:00"));
    }

    @Test public void anUnreadableTimestampIsZeroRatherThanAnException() {
        assertEquals(0L, LedgerProjection.millisOf(null));
        assertEquals(0L, LedgerProjection.millisOf(JSONObject.NULL));
        assertEquals(0L, LedgerProjection.millisOf(""));
        assertEquals(0L, LedgerProjection.millisOf("not a date"));
        assertEquals(7L, LedgerProjection.millisOf(7L));
    }

    // ------------------------------------------------------------------ the folds

    @Test public void theServerRowsComeThroughWithTheirBalancesAndTotals() throws Exception {
        JSONObject view = LedgerProjection.view(export(serverCache(), new JSONArray()), true, SELF, "سالم");

        assertEquals(1, view.getJSONArray("contacts").length());
        JSONObject contact = view.getJSONArray("contacts").getJSONObject(0);
        assertEquals("c-1", contact.getString("id"));
        assertEquals("أبو أحمد", contact.getString("name"));
        assertFalse(contact.getBoolean("pending"));
        assertEquals(utc(2026, 10, 10, 6, 0), contact.getLong("createdAtMillis"));

        JSONObject debt = view.getJSONArray("debts").getJSONObject(0);
        assertEquals("d-1", debt.getString("id"));
        assertEquals(10000L, debt.getLong("amount_cents"));
        assertEquals(2500L, debt.getLong("remainingCents"));
        assertEquals(2500L, debt.getLong("confirmedRemainingCents"));

        // A receivable of 25.00 minus a payment of 10.00 leaves 15.00 outstanding.
        assertEquals(1, view.getJSONArray("payments").length());
        assertEquals("2500", view.getString("receivableCents"));
        assertEquals("0", view.getString("payableCents"));
        assertTrue(view.getBoolean("canAdd"));
        assertEquals(7L, view.getLong("cursor"));
    }

    @Test public void aQueuedEntryIsVisibleAtOnceAndMarkedPending() throws Exception {
        JSONObject entry = entry(LedgerCommand.CONTACT_CREATE, "c-new", new JSONObject()
                .put("name", "زبون جديد").put("phone", "").put("category", "عميل").put("note", ""), SELF, "queued", new JSONArray());
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray().put(entry)), true, SELF, "سالم");

        assertEquals(1, view.getJSONArray("contacts").length());
        JSONObject contact = view.getJSONArray("contacts").getJSONObject(0);
        assertEquals("c-new", contact.getString("id"));
        assertTrue(contact.getBoolean("pending"));
        // The ledger records the installation, not the person, so the name is stamped from the device.
        assertEquals("سالم", contact.getString("createdBy"));
        assertEquals(1, view.getInt("pendingContacts"));
    }

    @Test public void aQueuedEntryFromAnotherDeviceIsNotAttributedToThisOperator() throws Exception {
        JSONObject entry = entry(LedgerCommand.CONTACT_CREATE, "c-other", new JSONObject().put("name", "من جهاز آخر"),
                "installation-other", "queued", new JSONArray());
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray().put(entry)), true, SELF, "سالم");
        assertEquals("", view.getJSONArray("contacts").getJSONObject(0).getString("createdBy"));
    }

    @Test public void aQueuedDebtAndPaymentShowAgainstTheirSleepingParents() throws Exception {
        JSONObject contact = entry(LedgerCommand.CONTACT_CREATE, "c-1", new JSONObject().put("name", "زبون"), SELF, "queued", new JSONArray());
        JSONObject debt = entry(LedgerCommand.DEBT_CREATE, "d-1", new JSONObject()
                .put("contactId", "c-1").put("amountCents", 5000L).put("direction", "receivable"), SELF, "queued", new JSONArray());
        JSONObject payment = entry(LedgerCommand.PAYMENT_CREATE, "p-1", new JSONObject()
                .put("debtId", "d-1").put("amountCents", 2000L).put("method", "cash"), SELF, "queued", new JSONArray());
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray().put(contact).put(debt).put(payment)), true, SELF, "سالم");

        assertEquals(1, view.getJSONArray("debts").length());
        JSONObject folded = view.getJSONArray("debts").getJSONObject(0);
        assertEquals(5000L, folded.getLong("currentAmountCents"));
        // A payment that has not been accepted yet still reduces what the store sees owing.
        assertEquals(3000L, folded.getLong("remainingCents"));
        // What the server has confirmed is still the full amount: that is the difference the screen shows.
        assertEquals(0L, folded.getLong("confirmedRemainingCents"));
        assertTrue(folded.getBoolean("hasPending"));
        assertEquals(1, view.getJSONArray("payments").length());
        assertEquals("p-1", view.getJSONArray("payments").getJSONObject(0).getString("id"));
        assertEquals("3000", view.getString("receivableCents"));
        assertEquals("0", view.getString("confirmedReceivableCents"));
    }

    @Test public void aPaymentLargerThanItsDebtIsNotProjected() throws Exception {
        JSONObject contact = entry(LedgerCommand.CONTACT_CREATE, "c-1", new JSONObject().put("name", "زبون"), SELF, "queued", new JSONArray());
        JSONObject debt = entry(LedgerCommand.DEBT_CREATE, "d-1", new JSONObject()
                .put("contactId", "c-1").put("amountCents", 1000L).put("direction", "receivable"), SELF, "queued", new JSONArray());
        JSONObject payment = entry(LedgerCommand.PAYMENT_CREATE, "p-1", new JSONObject()
                .put("debtId", "d-1").put("amountCents", 1500L).put("method", "cash"), SELF, "queued", new JSONArray());
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray().put(contact).put(debt).put(payment)), true, SELF, "سالم");
        assertEquals(0, view.getJSONArray("payments").length());
        assertEquals(1000L, view.getJSONArray("debts").getJSONObject(0).getLong("remainingCents"));
    }

    @Test public void aDebtWhoseContactWasRejectedIsNotShownEvenWhenTheDebtWasAccepted() throws Exception {
        JSONObject rejected = entry(LedgerCommand.CONTACT_CREATE, "c-1", new JSONObject().put("name", "مرفوض"), SELF, "rejected", new JSONArray());
        JSONObject dependent = entry(LedgerCommand.DEBT_CREATE, "d-1", new JSONObject()
                        .put("contactId", "c-1").put("amountCents", 1000L).put("direction", "receivable"), SELF, "queued",
                new JSONArray().put(rejected.getJSONObject("command").getString("operationId")));
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray().put(rejected).put(dependent)), true, SELF, "سالم");
        assertEquals(0, view.getJSONArray("contacts").length());
        assertEquals(0, view.getJSONArray("debts").length());
        assertTrue(dependent.getBoolean("projectionExcluded"));
    }

    @Test public void entriesAnotherInstallationQueuedAreProjectedButNotAttributedToThisOperator() throws Exception {
        // A recovery import can put entries owned by an older installation into this device's queue;
        // they are still part of what the store sees, they just are not this operator's work.
        JSONObject contact = entry(LedgerCommand.CONTACT_CREATE, "c-1", new JSONObject().put("name", "زبون"), "installation-old", "queued", new JSONArray());
        JSONObject debt = entry(LedgerCommand.DEBT_CREATE, "d-9", new JSONObject()
                .put("contactId", "c-1").put("amountCents", 100L).put("direction", "receivable"), "installation-old", "queued", new JSONArray());
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray().put(contact).put(debt)), true, SELF, "سالم");
        assertEquals(1, view.getJSONArray("debts").length());
        assertEquals("", view.getJSONArray("debts").getJSONObject(0).getString("createdBy"));
        assertEquals(100L, view.getJSONArray("debts").getJSONObject(0).getLong("remainingCents"));
    }

    @Test public void aSettledEntryIsNotProjectedTwice() throws Exception {
        JSONObject confirmed = entry(LedgerCommand.CONTACT_CREATE, "c-1", new JSONObject().put("name", "زبون"), SELF, "confirmed", new JSONArray());
        JSONObject cache = serverCache();
        JSONObject view = LedgerProjection.view(export(cache, new JSONArray().put(confirmed)), true, SELF, "سالم");
        // Once the server has it, the row comes from the snapshot — not as a second, pending contact.
        assertEquals(1, view.getJSONArray("contacts").length());
        assertFalse(view.getJSONArray("contacts").getJSONObject(0).getBoolean("pending"));
        assertEquals(0, view.getInt("pendingContacts"));
    }

    @Test public void anArchivedContactIsWithheldButItsVersionIsKeptForARestore() throws Exception {
        JSONObject cache = serverCache();
        cache.getJSONArray("contacts").getJSONObject(0).put("archived", true).put("version", 5L);
        JSONObject view = LedgerProjection.view(export(cache, new JSONArray()), true, SELF, "سالم");
        assertEquals(0, view.getJSONArray("contacts").length());
        assertEquals(5L, view.getJSONObject("archivedVersions").optLong("c-1"));
        // The debts of an archived contact cannot be reached through it, so they do not appear either.
        assertEquals(0, view.getJSONArray("debts").length());
    }

    @Test public void aReversedPaymentKeepsTheDebtWholeWhenBalancesAreAbsent() throws Exception {
        JSONObject cache = serverCache();
        cache.remove("debtBalances");
        cache.put("reversals", new JSONArray().put(new JSONObject().put("payment_id", "p-1")));
        JSONObject view = LedgerProjection.view(export(cache, new JSONArray()), true, SELF, "سالم");
        JSONObject debt = view.getJSONArray("debts").getJSONObject(0);
        assertEquals(10000L, debt.getLong("remainingCents"));
        assertEquals("10000", view.getString("receivableCents"));
        assertTrue(view.getJSONArray("payments").getJSONObject(0).getBoolean("reversed"));
    }

    @Test public void anEmptyDeviceAsksForAnEntryRatherThanHidingTheQueue() throws Exception {
        JSONObject view = LedgerProjection.view(export(emptyCache(), new JSONArray()), false, SELF, "");
        assertFalse(view.getBoolean("canAdd"));
        assertEquals("", view.getString("epoch"));
        assertEquals(0, view.getJSONArray("contacts").length());
    }

    // ------------------------------------------------------------------ fixtures

    private static JSONObject export(JSONObject cache, JSONArray entries) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("scope", "vhftjmiltqfwmrszdtgf|2|user-1")
                .put("cache", cache).put("entries", entries);
    }

    private static JSONObject emptyCache() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("storeId", 2).put("epoch", "").put("cursor", 0)
                .put("contacts", new JSONArray()).put("debts", new JSONArray()).put("payments", new JSONArray())
                .put("reversals", new JSONArray()).put("outcomes", new JSONArray());
    }

    private static JSONObject serverCache() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("storeId", 2)
                .put("epoch", "epoch-1").put("cursor", 7)
                .put("contacts", new JSONArray().put(new JSONObject()
                        .put("id", "c-1").put("name", "أبو أحمد").put("phone", "+970599123456")
                        .put("category", "عميل").put("note", "").put("archived", false).put("version", 1)
                        .put("created_at", "2026-10-10T06:00:00+00:00")))
                .put("debts", new JSONArray().put(new JSONObject()
                        .put("id", "d-1").put("contact_id", "c-1").put("direction", "receivable")
                        .put("amount_cents", 10000L).put("note", "").put("due_date", "")
                        .put("created_at", "2026-10-10T06:05:00+00:00")))
                .put("payments", new JSONArray().put(new JSONObject()
                        .put("id", "p-1").put("debt_id", "d-1").put("amount_cents", 7500L)
                        .put("method", "cash").put("note", "").put("created_at", "2026-10-10T07:00:00+00:00")))
                .put("debtBalances", new JSONArray().put(new JSONObject()
                        .put("debtId", "d-1").put("amountCents", 10000L).put("paidCents", 7500L).put("remainingCents", 2500L).put("version", 1)))
                .put("reversals", new JSONArray()).put("outcomes", new JSONArray());
    }

    private static JSONObject entry(String type, String entityId, JSONObject payload, String installation,
                                    String state, JSONArray dependsOn) throws Exception {
        JSONObject command = LedgerCommand.of(type, entityId, payload, installation, 1, "epoch-1", dependsOn,
                "2026-10-12T10:00:00.000+03:00");
        return new JSONObject().put("command", command).put("wire", command.toString())
                .put("state", state).put("sentEver", false).put("attempts", 0).put("history", new JSONArray());
    }
}
