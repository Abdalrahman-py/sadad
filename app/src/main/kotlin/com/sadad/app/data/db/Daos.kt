package com.sadad.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
  @Query("SELECT * FROM contacts ORDER BY createdSeq") suspend fun all(): List<ContactEntity>

  @Query("SELECT * FROM contacts WHERE id = :id") suspend fun byId(id: String): ContactEntity?

  @Query("SELECT COUNT(*) FROM contacts") suspend fun count(): Int

  @Query("SELECT * FROM contacts WHERE id IN (:ids)")
  suspend fun byIds(ids: List<String>): List<ContactEntity>

  @Query("SELECT * FROM contacts WHERE archived = 1 ORDER BY createdSeq")
  suspend fun archived(): List<ContactEntity>

  @Query("SELECT name FROM contacts WHERE id = :id") suspend fun name(id: String): String?

  /** The ids of stored Contacts carrying [phone]; the contact form's duplicate check reads it. */
  @Query("SELECT id FROM contacts WHERE phone = :phone")
  suspend fun idsWithPhone(phone: String): List<String>

  @Upsert suspend fun upsert(row: ContactEntity)

  @Query("SELECT createdSeq FROM contacts WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM contacts WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM contacts") suspend fun clear()
}

@Dao
interface DebtDao {
  @Query("SELECT * FROM debts ORDER BY createdSeq") suspend fun all(): List<DebtEntity>

  @Query("SELECT * FROM debts WHERE contactId = :contactId ORDER BY createdSeq")
  suspend fun ofContact(contactId: String): List<DebtEntity>

  /** The debts created in `[from, to)` and the debts of the payments created in it, in epoch ms. */
  @Query(
      """
      SELECT * FROM debts
      WHERE (createdAt >= :from AND createdAt < :to)
      OR id IN (SELECT debtId FROM payments WHERE createdAt >= :from AND createdAt < :to)
      """
  )
  suspend fun inPeriod(from: Long, to: Long): List<DebtEntity>

  @Query("SELECT * FROM debts WHERE id IN (:ids)")
  suspend fun byIds(ids: List<String>): List<DebtEntity>

  /**
   * Home's month, confirmed part: the sum of `amount` of RECEIVABLE debts whose contact is not
   * archived and whose `createdAt` is in `[from, to)`, in epoch ms.
   */
  @Query(
      """
      SELECT COALESCE(SUM(d.amountCents), 0) FROM debts d
      JOIN contacts c ON c.id = d.contactId
      WHERE d.direction = 'receivable' AND c.archived = 0 AND d.createdAt >= :from AND d.createdAt < :to
      """
  )
  fun monthReceivableDebts(from: Long, to: Long): Flow<Long>

  @Query("SELECT contactId FROM debts WHERE id = :id") suspend fun contactId(id: String): String?

  @Upsert suspend fun upsert(row: DebtEntity)

  @Query("SELECT createdSeq FROM debts WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM debts WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM debts") suspend fun clear()
}

@Dao
interface PaymentDao {
  @Query("SELECT * FROM payments ORDER BY createdSeq") suspend fun all(): List<PaymentEntity>

  /**
   * The payments on one person's debts; the join is by contact, so it scales past the variable
   * limit.
   */
  @Query(
      "SELECT p.* FROM payments p JOIN debts d ON d.id = p.debtId WHERE d.contactId = :contactId ORDER BY p.createdSeq"
  )
  suspend fun ofContact(contactId: String): List<PaymentEntity>

  @Query("SELECT * FROM payments WHERE debtId IN (:debtIds)")
  suspend fun onDebts(debtIds: List<String>): List<PaymentEntity>

  @Query("SELECT * FROM payments WHERE id IN (:ids)")
  suspend fun byIds(ids: List<String>): List<PaymentEntity>

  /**
   * Home's month, confirmed part: the sum and count of non-reversed payments whose debt is
   * RECEIVABLE, whose contact is not archived and whose `createdAt` is in `[from, to)`.
   */
  @Query(
      """
      SELECT COALESCE(SUM(p.amountCents), 0) FROM payments p
      JOIN debts d ON d.id = p.debtId JOIN contacts c ON c.id = d.contactId
      WHERE d.direction = 'receivable' AND c.archived = 0 AND p.reversed = 0
        AND p.createdAt >= :from AND p.createdAt < :to
      """
  )
  fun monthReceivablePayments(from: Long, to: Long): Flow<Long>

  @Query(
      """
      SELECT COUNT(*) FROM payments p
      JOIN debts d ON d.id = p.debtId JOIN contacts c ON c.id = d.contactId
      WHERE d.direction = 'receivable' AND c.archived = 0 AND p.reversed = 0
        AND p.createdAt >= :from AND p.createdAt < :to
      """
  )
  fun monthReceivablePaymentsCount(from: Long, to: Long): Flow<Int>

  @Query("SELECT debtId FROM payments WHERE id = :id") suspend fun debtId(id: String): String?

  @Upsert suspend fun upsert(row: PaymentEntity)

  @Query("SELECT createdSeq FROM payments WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM payments WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM payments") suspend fun clear()
}

@Dao
interface OffsetDao {
  @Query("SELECT * FROM offsets ORDER BY createdSeq") suspend fun all(): List<OffsetEntity>

  /** The offsets on either side of one person's debts, both sides joined by contact id. */
  @Query(
      """
      SELECT o.* FROM offsets o
      JOIN debts dr ON dr.id = o.receivableDebtId
      JOIN debts dp ON dp.id = o.payableDebtId
      WHERE dr.contactId = :contactId OR dp.contactId = :contactId
      ORDER BY o.createdSeq
      """
  )
  suspend fun ofContact(contactId: String): List<OffsetEntity>

  @Query(
      "SELECT * FROM offsets WHERE receivableDebtId IN (:debtIds) OR payableDebtId IN (:debtIds)"
  )
  suspend fun onDebts(debtIds: List<String>): List<OffsetEntity>

  @Query("SELECT * FROM offsets WHERE id IN (:ids)")
  suspend fun byIds(ids: List<String>): List<OffsetEntity>

  @Upsert suspend fun upsert(row: OffsetEntity)

  @Query("SELECT createdSeq FROM offsets WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM offsets WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM offsets") suspend fun clear()
}

@Dao
interface LedgerMetaDao {
  @Query("SELECT * FROM ledger_meta WHERE id = 0") suspend fun get(): LedgerMetaEntity?

  @Upsert suspend fun put(row: LedgerMetaEntity)

  @Query("DELETE FROM ledger_meta") suspend fun clear()
}

@Dao
interface EventDao {
  /** Fails on a sequence the table already holds, which rolls the page's transaction back. */
  @Insert suspend fun insert(events: List<EventEntity>)

  @Query("SELECT * FROM events ORDER BY sequence") suspend fun all(): List<EventEntity>

  @Query(
      "DELETE FROM events WHERE sequence NOT IN (SELECT sequence FROM events ORDER BY sequence DESC LIMIT :keep)"
  )
  suspend fun trim(keep: Int)

  @Query("DELETE FROM events") suspend fun clear()
}

@Dao
interface InstallationNameDao {
  @Upsert suspend fun upsert(names: List<InstallationNameEntity>)

  @Query("SELECT * FROM installation_names") fun observe(): Flow<List<InstallationNameEntity>>
}

@Dao
interface ContactSummaryDao {
  @Upsert suspend fun upsert(rows: List<ContactSummaryEntity>)

  @Query("DELETE FROM contact_summary WHERE contactId IN (:ids)")
  suspend fun delete(ids: List<String>)

  @Query("DELETE FROM contact_summary") suspend fun clear()

  @Query("SELECT COUNT(*) FROM contact_summary") suspend fun count(): Int

  @Query("SELECT COUNT(*) FROM contact_summary") fun observeCount(): Flow<Int>

  @Query("SELECT * FROM contact_summary") suspend fun all(): List<ContactSummaryEntity>

  /** Home's «لك عند الناس»: `SUM(net)` over the positive nets. */
  @Query("SELECT COALESCE(SUM(net), 0) FROM contact_summary WHERE net > 0")
  fun owedTotal(): Flow<Long>

  /** Home's «عليك للناس»: `-SUM(net)` over the negative nets. */
  @Query("SELECT COALESCE(-SUM(net), 0) FROM contact_summary WHERE net < 0")
  fun owesTotal(): Flow<Long>

  /**
   * Home's follow-up candidates: `net > 0` and `earliestOpenDue <= today` (ISO dates compare as
   * text). Earliest due first is `homeUiState`'s late-days order.
   */
  @Query(
      """
      SELECT * FROM contact_summary
      WHERE net > 0 AND earliestOpenDue IS NOT NULL AND earliestOpenDue <= :today
      ORDER BY earliestOpenDue ASC, net DESC, name ASC
      """
  )
  fun followUpCandidates(today: String): Flow<List<ContactSummaryEntity>>

  @Query("SELECT * FROM contact_summary") fun observe(): Flow<List<ContactSummaryEntity>>
}

/** One answered command for [OutboxDao.settle]; the SQL keeps a cancelled row cancelled. */
data class OutboxAnswer(val operationId: String, val state: String, val code: String?)

@Dao
interface OutboxDao {
  @Insert suspend fun insert(row: OutboxEntity): Long

  @Insert suspend fun insertAll(rows: List<OutboxEntity>)

  @Query("SELECT * FROM outbox ORDER BY seq") suspend fun all(): List<OutboxEntity>

  @Query("SELECT * FROM outbox ORDER BY seq") fun observe(): Flow<List<OutboxEntity>>

  @Query("SELECT * FROM outbox WHERE state = 'queued' ORDER BY seq LIMIT :limit")
  suspend fun queued(limit: Int): List<OutboxEntity>

  @Query(
      "UPDATE outbox SET recoveryPermit = :permit WHERE state = 'queued' AND operationId IN (:operationIds)"
  )
  suspend fun setRecoveryPermit(operationIds: List<String>, permit: String)

  @Query(
      "UPDATE outbox SET state = :state, code = :code WHERE operationId = :operationId AND state = 'queued'"
  )
  suspend fun markAnswered(operationId: String, state: String, code: String?)

  @Transaction
  suspend fun settle(answers: List<OutboxAnswer>) {
    for (answer in answers) markAnswered(answer.operationId, answer.state, answer.code)
  }

  @Query(
      "UPDATE outbox SET state = 'cancelled' WHERE operationId IN (:operationIds) AND state = 'queued'"
  )
  suspend fun cancel(operationIds: List<String>)

  @Query(
      "DELETE FROM outbox WHERE operationId = :operationId AND state IN ('rejected', 'cancelled', 'unsendable')"
  )
  suspend fun deleteDismissed(operationId: String)

  @Query("DELETE FROM outbox WHERE state = 'accepted' AND operationId IN (:operationIds)")
  suspend fun deleteAccepted(operationIds: List<String>)
}
