package com.sadad.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * One database per store, file `ledger-<storeUserId>.db`: the confirmed Ledger rows, the cursor,
 * the latest change events, the stored summaries and the outbox. All data is test data, so schema 6
 * is destructive: every older version drops and recreates, and only a downgrade — an older build
 * opening a newer file, which happens between development branches — also drops.
 */
@Database(
    entities =
        [
            ContactEntity::class,
            DebtEntity::class,
            PaymentEntity::class,
            OffsetEntity::class,
            LedgerMetaEntity::class,
            EventEntity::class,
            OutboxEntity::class,
            InstallationNameEntity::class,
            ContactSummaryEntity::class,
        ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {
  abstract fun contactDao(): ContactDao

  abstract fun debtDao(): DebtDao

  abstract fun paymentDao(): PaymentDao

  abstract fun offsetDao(): OffsetDao

  abstract fun metaDao(): LedgerMetaDao

  abstract fun eventDao(): EventDao

  abstract fun outboxDao(): OutboxDao

  abstract fun installationNameDao(): InstallationNameDao

  abstract fun contactSummaryDao(): ContactSummaryDao

  companion object {
    fun open(context: Context, storeUserId: String): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger-$storeUserId.db")
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4, 5)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
  }
}
