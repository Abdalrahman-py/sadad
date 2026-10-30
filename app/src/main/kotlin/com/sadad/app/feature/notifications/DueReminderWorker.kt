package com.sadad.app.feature.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sadad.app.AppGraph
import com.sadad.app.MainActivity
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.app.data.ledger.Outbox
import com.sadad.app.data.ledger.failedOperations
import com.sadad.app.data.ledger.pendingCommands
import com.sadad.app.ui.components.formatCents
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.view
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens separate read connections; never changes the account displayed by the UI. */
class DueReminderWorker
@JvmOverloads
constructor(
    context: Context,
    parameters: WorkerParameters,
    private val suppliedGraph: AppGraph? = null,
    private val suppliedReminders: DueReminders? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        try {
          val graph = suppliedGraph ?: (applicationContext as SadadApplication).graph
          val reminders = suppliedReminders ?: DueReminders.get(applicationContext)
          val today = LocalDate.now(clock)
          for (account in graph.accounts.file().accounts) {
            val database = LedgerDatabase.open(applicationContext, account.storeUserId)
            val current =
                try {
                  database.withTransaction {
                    val state = LedgerStore(database).load()
                    val rows = Outbox(database.outboxDao(), graph.dataKey.key).snapshot()
                    state.view(
                        pendingCommands(rows),
                        failedOperations(rows),
                        account.installationId,
                        account.staffName,
                    )
                  }
                } finally {
                  database.close()
                }
            val contacts = current.contacts.associateBy { it.id }
            for (debt in current.debts) {
              if (debt.direction != Direction.RECEIVABLE || debt.remaining <= Cents.ZERO) continue
              val stage =
                  when (debt.dueDate) {
                    today.toString() -> "today"
                    today.plusDays(1).toString() -> "tomorrow"
                    else -> continue
                  }
              val contact = contacts[debt.contactId] ?: continue
              val event =
                  Reminder(
                      account.storeUserId,
                      debt.id,
                      contact.id,
                      contact.name,
                      account.storeName.ifBlank { account.username },
                      debt.remaining,
                      debt.dueDate!!,
                      stage,
                      clock.millis(),
                  )
              // Record only what was actually shown: a denied permission must stay unrecorded so
              // the next run can post it instead of the reminder disappearing as "already sent".
              if (reminders.wasShown(event)) continue
              if (post(event)) reminders.record(event)
            }
          }
          Result.success()
        } catch (_: IOException) {
          Result.retry()
        }
      }

  /** Returns false when nothing was posted, so the caller can leave the reminder unsent. */
  private fun post(event: Reminder): Boolean {
    val context = applicationContext
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(
          NotificationChannel(
              CHANNEL_ID,
              context.getString(R.string.reminder_channel),
              NotificationManager.IMPORTANCE_DEFAULT,
          )
      )
    }
    if (
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    )
        return false
    val intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("sadad://reminder/${Uri.encode(event.id)}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_STORE, event.storeUserId)
            .putExtra(EXTRA_CONTACT, event.contactId)
    val pending =
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.contactName)
            .setContentText(reminderText(context, event))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    manager.notify(event.id, 0, notification)
    return true
  }

  companion object {
    const val CHANNEL_ID = "sadad_due_dates"
    const val EXTRA_STORE = "storeUserId"
    const val EXTRA_CONTACT = "contactId"

    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
      val request =
          PeriodicWorkRequestBuilder<DueReminderWorker>(24, TimeUnit.HOURS)
              .setInitialDelay(reminderInitialDelay(now).toMillis(), TimeUnit.MILLISECONDS)
              .build()
      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork("due-reminders", ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}

internal fun reminderText(context: Context, reminder: Reminder): String =
    context.getString(
        if (reminder.stage == "today") R.string.reminder_today else R.string.reminder_tomorrow,
        formatCents(reminder.amountCents),
        reminder.storeName,
    )
