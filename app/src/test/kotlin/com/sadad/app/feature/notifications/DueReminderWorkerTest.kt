package com.sadad.app.feature.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.sadad.app.data.db.Events
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DueReminderWorkerTest {
  @Test
  fun checksEveryStoreAndOnlyPostsEachOpenDueDebtOnce() = runTest {
    val fixture = TestGraph.signedIn()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val context = fixture.context
      shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      val reminders =
          DueReminders(
              File(context.cacheDir, "reminders-${System.nanoTime()}.preferences_pb"),
              scope,
          )
      val store = fixture.graph.current.value!!
      val contact = Commands.contactCreate(Events.origin, Events.at(0), "سامي")
      fun debt(amount: Long, date: String) =
          Commands.debtCreate(
              Events.origin,
              Events.at(1),
              contact.entityId,
              Direction.RECEIVABLE,
              Cents(amount),
              dueDate = date,
          )
      val today = debt(12000, "2026-10-17")
      val tomorrow = debt(5000, "2026-10-18")
      val later = debt(3000, "2026-10-19")
      val paid = debt(100, "2026-10-17")
      val payment =
          Commands.paymentCreate(
              Events.origin,
              Events.at(2),
              paid.entityId,
              Cents(100),
              PaymentMethod.CASH,
          )
      val archived = Commands.contactCreate(Events.origin, Events.at(0), "مؤرشف")
      val hidden =
          Commands.debtCreate(
              Events.origin,
              Events.at(1),
              archived.entityId,
              Direction.RECEIVABLE,
              Cents(100),
              dueDate = "2026-10-17",
          )
      val archive =
          Commands.contactArchive(
              Events.origin,
              Events.at(2),
              archived.entityId,
              1,
              "أرشفة",
              entityId = archived.entityId,
          )
      val commands =
          listOf(contact, today, tomorrow, later, paid, payment, archived, hidden, archive)
      store.repository.applyPage(
          Events.page(commands.mapIndexed { i, c -> Events.accepted(c, i + 1L) })
      )
      fixture.signIn(STORE_B)
      val second = fixture.activate(STORE_B)
      val otherContact = Commands.contactCreate(Events.origin, Events.at(0), "خالد")
      val otherDebt =
          Commands.debtCreate(
              Events.origin,
              Events.at(1),
              otherContact.entityId,
              Direction.RECEIVABLE,
              Cents(700),
              dueDate = "2026-10-17",
          )
      second.repository.applyPage(
          Events.page(listOf(Events.accepted(otherContact, 1), Events.accepted(otherDebt, 2)))
      )
      val clock = Clock.fixed(Instant.parse("2026-10-17T05:00:00Z"), ZoneId.of("Asia/Gaza"))
      fun worker(): DueReminderWorker =
          TestListenableWorkerBuilder<DueReminderWorker>(context)
              .setWorkerFactory(
                  object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        DueReminderWorker(
                            appContext,
                            workerParameters,
                            fixture.graph,
                            reminders,
                            clock,
                        )
                  }
              )
              .build()
      assertEquals(ListenableWorker.Result.success(), worker().doWork())
      val manager = context.getSystemService(NotificationManager::class.java)
      val notifications = shadowOf(manager).allNotifications
      assertEquals(3, notifications.size)
      val texts = notifications.map { it.extras.getString(Notification.EXTRA_TEXT).orEmpty() }
      assertTrue(texts.any { it.contains("يستحق اليوم: 120 ₪") })
      assertTrue(texts.any { it.contains("يستحق غداً: 50 ₪") })
      assertEquals(2, reminders.inbox(STORE_A).first().size)
      assertEquals(1, reminders.inbox(STORE_B).first().size)
      manager.cancelAll()
      assertEquals(ListenableWorker.Result.success(), worker().doWork())
      assertTrue(shadowOf(manager).allNotifications.isEmpty())
    } finally {
      scope.cancel()
      fixture.close()
    }
  }
  @Test
  fun aReminderThatCannotBePostedStaysUnsentAndFiresOnTheNextRun() = runTest {
    val fixture = TestGraph.signedIn()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val context = fixture.context
      // Nothing is granted yet, so the post is refused.
      val reminders =
          DueReminders(
              File(context.cacheDir, "reminders-${System.nanoTime()}.preferences_pb"),
              scope,
          )
      val store = fixture.graph.current.value!!
      val contact = Commands.contactCreate(Events.origin, Events.at(0), "سامي")
      val debt =
          Commands.debtCreate(
              Events.origin,
              Events.at(1),
              contact.entityId,
              Direction.RECEIVABLE,
              Cents(12000),
              dueDate = "2026-10-17",
          )
      store.repository.applyPage(
          Events.page(listOf(Events.accepted(contact, 1), Events.accepted(debt, 2)))
      )
      val clock = Clock.fixed(Instant.parse("2026-10-17T05:00:00Z"), ZoneId.of("Asia/Gaza"))
      fun worker(): DueReminderWorker =
          TestListenableWorkerBuilder<DueReminderWorker>(context)
              .setWorkerFactory(
                  object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        DueReminderWorker(
                            appContext,
                            workerParameters,
                            fixture.graph,
                            reminders,
                            clock,
                        )
                  }
              )
              .build()
      val manager = context.getSystemService(NotificationManager::class.java)

      assertEquals(ListenableWorker.Result.success(), worker().doWork())
      assertTrue(
          "a reminder that was never shown must not be recorded as sent",
          reminders.inbox(STORE_A).first().isEmpty(),
      )
      assertTrue(shadowOf(manager).allNotifications.isEmpty())

      shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      assertEquals(ListenableWorker.Result.success(), worker().doWork())
      assertEquals(1, reminders.inbox(STORE_A).first().size)
      assertEquals(1, shadowOf(manager).allNotifications.size)
    } finally {
      scope.cancel()
      fixture.close()
    }
  }
}
