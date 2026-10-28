package com.sadad.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * One database per store, file `ledger-<storeUserId>.db`: the confirmed Ledger rows, the cursor,
 * the latest change events and the outbox. The local file is the store's only copy of rows it has
 * not pushed yet, so a schema change carries a migration and keeps the rows; only a downgrade — an
 * older build opening a newer file, which happens between development branches — falls back to
 * dropping the tables, and that never ships.
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
        ],
    version = 4,
    exportSchema = true,
    autoMigrations =
        [
            AutoMigration(from = 1, to = 2),
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
        ],
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

  companion object {
    fun open(context: Context, storeUserId: String): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger-$storeUserId.db")
            // Upgrades keep the rows (the migrations above). Only a downgrade drops them: that is
            // an
            // older build meeting a newer file, which happens between development branches and
            // must not cost a store its unsent work on a real update.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
  }
}
