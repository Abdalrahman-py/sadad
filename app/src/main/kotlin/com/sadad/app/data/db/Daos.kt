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

  @Upsert suspend fun upsert(row: ContactEntity)

  @Query("SELECT createdSeq FROM contacts WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM contacts WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM contacts") suspend fun clear()
}

@Dao
interface DebtDao {
  @Query("SELECT * FROM debts ORDER BY createdSeq") suspend fun all(): List<DebtEntity>

  @Upsert suspend fun upsert(row: DebtEntity)

  @Query("SELECT createdSeq FROM debts WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM debts WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM debts") suspend fun clear()
}

@Dao
interface PaymentDao {
  @Query("SELECT * FROM payments ORDER BY createdSeq") suspend fun all(): List<PaymentEntity>

  @Upsert suspend fun upsert(row: PaymentEntity)

  @Query("SELECT createdSeq FROM payments WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM payments WHERE id IN (:ids)") suspend fun delete(ids: List<String>)

  @Query("DELETE FROM payments") suspend fun clear()
}

@Dao
interface OffsetDao {
  @Query("SELECT * FROM offsets ORDER BY createdSeq") suspend fun all(): List<OffsetEntity>

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
