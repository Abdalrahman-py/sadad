package com.sadad.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * One database per store, file `ledger-<storeUserId>.db`: the confirmed Ledger rows, the cursor,
 * the latest change events and the outbox. Version 1 with no migrations: until Phase 4 a schema
 * change means testers reinstall (all data is test data).
 */
@Database(
    entities =
        [
            ContactEntity::class,
            DebtEntity::class,
            PaymentEntity::class,
            LedgerMetaEntity::class,
            EventEntity::class,
            OutboxEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {
  abstract fun contactDao(): ContactDao

  abstract fun debtDao(): DebtDao

  abstract fun paymentDao(): PaymentDao

  abstract fun metaDao(): LedgerMetaDao

  abstract fun eventDao(): EventDao

  abstract fun outboxDao(): OutboxDao

  companion object {
    fun open(context: Context, storeUserId: String): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger-$storeUserId.db").build()
  }
}
