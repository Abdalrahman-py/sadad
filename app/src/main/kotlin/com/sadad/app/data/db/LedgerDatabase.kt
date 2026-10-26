package com.sadad.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * One database per store, file `ledger-<storeUserId>.db`: the confirmed Ledger rows, the cursor,
 * the latest change events and the outbox. A schema change bumps the version and falls back
 * destructively (all data is test data); the store re-syncs from zero.
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
    version = 2,
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
        Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger-$storeUserId.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
  }
}
