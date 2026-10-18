package com.sadad.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ContactDao {
  @Query("SELECT * FROM contacts ORDER BY createdSeq") suspend fun all(): List<ContactEntity>

  @Upsert suspend fun upsert(row: ContactEntity)

  @Query("SELECT createdSeq FROM contacts WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM contacts") suspend fun clear()
}

@Dao
interface DebtDao {
  @Query("SELECT * FROM debts ORDER BY createdSeq") suspend fun all(): List<DebtEntity>

  @Upsert suspend fun upsert(row: DebtEntity)

  @Query("SELECT createdSeq FROM debts WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM debts") suspend fun clear()
}

@Dao
interface PaymentDao {
  @Query("SELECT * FROM payments ORDER BY createdSeq") suspend fun all(): List<PaymentEntity>

  @Upsert suspend fun upsert(row: PaymentEntity)

  @Query("SELECT createdSeq FROM payments WHERE id = :id") suspend fun createdSeq(id: String): Long?

  @Query("DELETE FROM payments") suspend fun clear()
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
interface OutboxDao {
  @Insert suspend fun insert(row: OutboxEntity): Long

  @Query("SELECT * FROM outbox ORDER BY seq") suspend fun all(): List<OutboxEntity>
}
