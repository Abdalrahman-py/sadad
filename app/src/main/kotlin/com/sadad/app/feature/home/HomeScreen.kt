package com.sadad.app.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.LocalGraph
import com.sadad.app.R
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.requireGraph
import com.sadad.app.ui.components.MoneyText
import com.sadad.app.ui.components.formatShekels
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.components.softShadow
import com.sadad.app.ui.theme.sadadPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The handles the screen tests and the a11y sweep hold on to. */
object HomeTags {
  const val SCREEN = "screen:home"
  const val BELL = "home:bell"
  const val OWED = "home:owed"
  const val OWES = "home:owes"
  const val MONTH_LINE = "home:month"
  const val FOLLOW = "home:follow"
  const val SHOW_ALL = "home:show-all"
  const val OFFLINE = "home:offline"
  const val EMPTY_CTA = "home:empty-cta"
  const val AFTER_FIRST = "home:after-first"

  fun follow(contactId: String) = "home:follow-$contactId"

  fun remind(contactId: String) = "home:remind-$contactId"
}

/**
 * Two questions, nothing else: what people owe the store and what the store owes, then who is late
 * enough to chase. A new store sees how to start instead, and a store that cannot reach the server
 * is told how many entries wait and until when it may keep recording.
 */
@Composable
fun HomeScreen(
    onOpenContact: (String) -> Unit = {},
    onOpenPeople: (filter: String?) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onRecord: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
    viewModel: HomeViewModel = homeViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(viewModel, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.followMonth() }
  }
  HomeContent(
      state = state,
      unread = unreadReminders(),
      onOpenContact = onOpenContact,
      onOpenPeople = onOpenPeople,
      onOpenNotifications = onOpenNotifications,
      onRecord = onRecord,
      onOpenActivity = onOpenActivity,
  )
}

/** The open store's unread reminders, the count the bell shows. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun unreadReminders(): Int {
  val context = LocalContext.current
  // The screen tests run under UiTestApplication, which has no graph; they pass their own count.
  val graph = LocalGraph.current ?: return 0
  val reminders = remember(context) { DueReminders.get(context) }
  val unread by
      remember(graph, reminders) {
            graph.current.flatMapLatest { open ->
              if (open == null) flowOf(0) else reminders.unreadCount(open.account.storeUserId)
            }
          }
          .collectAsStateWithLifecycle(0)
  return unread
}

@Composable
private fun homeViewModel(): HomeViewModel {
  val graph = requireGraph()
  return viewModel(factory = viewModelFactory { initializer { HomeViewModel(graph) } })
}

@Composable
fun HomeContent(
    state: HomeUiState,
    unread: Int = 0,
    onOpenContact: (String) -> Unit = {},
    onOpenPeople: (filter: String?) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onRecord: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
) {
  val storeName = state.storeName.ifBlank { stringResource(R.string.home_default_store) }
  Column(Modifier.fillMaxSize().sadadPageBackground().testTag(HomeTags.SCREEN)) {
    HomeHeader(storeName = storeName, unread = unread, onOpenNotifications = onOpenNotifications)
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      val offline = state.offline
      if (offline != null) OfflineBanner(offline)
      if (state.empty) {
        EmptyStart(onRecord)
      } else {
        HeroCard(state, onOpenActivity)
        FollowUpSection(
            followUps = state.followUps,
            count = state.followUpCount,
            storeName = storeName,
            onOpenContact = onOpenContact,
            onOpenPeople = onOpenPeople,
        )
      }
    }
  }
}

/* ------------------------------------------------------------------ header */

@Composable
private fun HomeHeader(storeName: String, unread: Int, onOpenNotifications: () -> Unit) {
  val p = sadadPalette
  val today = LocalDate.now()
  val date = remember(today) { DATE_FORMAT.format(today) }
  val bellLabel =
      if (unread > 0) stringResource(R.string.home_bell_new, unread)
      else stringResource(R.string.home_bell)
  Row(
      Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(date, fontSize = 12.sp, lineHeight = 16.sp, color = p.muted)
      Text(
          storeName,
          fontSize = 21.sp,
          lineHeight = 28.sp,
          fontWeight = FontWeight.Bold,
          color = p.text,
      )
    }
    Box(Modifier.size(44.dp)) {
      Box(
          Modifier.size(44.dp)
              .clip(CircleShape)
              .background(p.card)
              .border(1.dp, p.line, CircleShape)
              .clickable(onClick = onOpenNotifications)
              .testTag(HomeTags.BELL)
              .semantics {
                contentDescription = bellLabel
                role = Role.Button
              },
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_notifications),
            contentDescription = null,
            tint = p.text,
            modifier = Modifier.size(22.dp),
        )
      }
      if (unread > 0) {
        Surface(
            color = BadgeRed,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).heightIn(min = 18.dp).widthIn(min = 18.dp),
        ) {
          Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text("$unread", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      }
    }
  }
}

/* ---------------------------------------------------------------- offline */

@Composable
private fun OfflineBanner(offline: HomeOffline) {
  val p = sadadPalette
  Surface(
      color = p.creditSoft,
      shape = RoundedCornerShape(13.dp),
      modifier = Modifier.fillMaxWidth().testTag(HomeTags.OFFLINE),
  ) {
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
          stringResource(R.string.home_offline_title, entriesLabel(offline.queued)),
          fontSize = 14.sp,
          lineHeight = 20.sp,
          fontWeight = FontWeight.Bold,
          color = p.text,
      )
      val until = offline.leaseEndsAt
      Text(
          if (until == null) stringResource(R.string.home_offline_body_no_time)
          else stringResource(R.string.home_offline_body, clockTime(until.toEpochMilli())),
          fontSize = 12.sp,
          lineHeight = 18.sp,
          color = p.muted,
      )
    }
  }
}

/* ------------------------------------------------------------------- hero */

@Composable
private fun HeroCard(state: HomeUiState, onOpenActivity: () -> Unit) {
  val p = sadadPalette
  val hero =
      if (p.isDark) listOf(Color(0xFF1F5E4F), Color(0xFF02302B))
      else listOf(Color(0xFF064B42), Color(0xFF02302B))
  Box(
      Modifier.fillMaxWidth()
          // The board's `0 12px 28px rgba(2, 48, 43, 0.22)` under the hero.
          .then(if (p.isDark) Modifier else Modifier.softShadow(18.dp, HeroShadow, 28.dp))
          .clip(RoundedCornerShape(18.dp))
          .background(Brush.linearGradient(hero))
          .padding(20.dp)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.home_owed_to_store),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = HeroInk,
        )
        MoneyText(
            cents = state.owedToStore,
            style = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.testTag(HomeTags.OWED),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              stringResource(R.string.home_store_owes),
              fontSize = 14.sp,
              lineHeight = 20.sp,
              color = HeroInk,
          )
          Spacer(Modifier.width(6.dp))
          MoneyText(
              cents = state.storeOwes,
              style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
              color = Color.White,
              modifier = Modifier.testTag(HomeTags.OWES),
          )
        }
      }
      Column(
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(13.dp))
              .background(HeroPanel)
              .clickable(onClick = onOpenActivity)
              .testTag(HomeTags.MONTH_LINE)
              .semantics { role = Role.Button }
              .padding(horizontal = 14.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              stringResource(R.string.home_month_debts),
              fontSize = 13.sp,
              lineHeight = 18.sp,
              color = HeroInk,
          )
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            MoneyText(
                cents = state.monthDebts,
                style =
                    TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Icon(
                painterResource(R.drawable.ic_chevron_start),
                null,
                tint = HeroInk,
                modifier = Modifier.size(16.dp),
            )
          }
        }
        CollectionBar(state.collectionPercent)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              stringResource(
                  R.string.home_month_collection,
                  moneyText(state.monthPayments),
                  paymentsLabel(state.monthPaymentsCount),
              ),
              fontSize = 14.sp,
              lineHeight = 20.sp,
              color = HeroInk,
          )
          Text(
              stringResource(R.string.home_month_share, state.collectionPercent),
              fontSize = 14.sp,
              lineHeight = 20.sp,
              color = Color.White,
          )
        }
      }
    }
  }
}

@Composable
private fun CollectionBar(percent: Int) {
  val description = stringResource(R.string.home_month_share, percent)
  Box(
      Modifier.fillMaxWidth()
          .height(8.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(HeroTrack)
          .semantics { contentDescription = description }
  ) {
    // The bar fills from empty when Home opens, then follows the month's figure.
    val target = percent.coerceIn(0, 100) / 100f
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val fraction by
        animateFloatAsState(if (started) target else 0f, tween(700, 120), label = "collection")
    if (fraction <= 0f) return@Box
    Box(
        Modifier.fillMaxWidth(fraction)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HeroBar)
    )
  }
}

/* -------------------------------------------------------------- follow-up */

@Composable
private fun FollowUpSection(
    followUps: List<HomeFollowUp>,
    count: Int,
    storeName: String,
    onOpenContact: (String) -> Unit,
    onOpenPeople: (filter: String?) -> Unit,
) {
  val p = sadadPalette
  val context = LocalContext.current
  val link = if (p.isDark) p.accent else p.primary
  Column(
      Modifier.fillMaxWidth().testTag(HomeTags.FOLLOW),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
            stringResource(R.string.home_follow_title),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            color = p.text,
        )
        if (count > 0) {
          Surface(color = p.owesSoft, shape = RoundedCornerShape(11.dp)) {
            Box(
                Modifier.heightIn(min = 22.dp)
                    .widthIn(min = 24.dp)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
              Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.owes)
            }
          }
        }
      }
      Box(
          Modifier.heightIn(min = 44.dp)
              .clickable { onOpenPeople("late") }
              .testTag(HomeTags.SHOW_ALL)
              .semantics { role = Role.Button },
          contentAlignment = Alignment.CenterEnd,
      ) {
        Text(
            stringResource(R.string.home_show_all),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = link,
        )
      }
    }
    Surface(
        color = p.card,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, p.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
      Column {
        if (followUps.isEmpty()) {
          Text(
              stringResource(R.string.home_follow_empty),
              fontSize = 14.sp,
              color = p.muted,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
          )
        }
        followUps.forEachIndexed { index, person ->
          if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
          FollowUpRow(
              person = person,
              onOpenContact = onOpenContact,
              onRemind = { openWhatsApp(context, person, storeName) },
          )
        }
      }
    }
    Text(
        stringResource(R.string.home_remind_note),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = p.muted,
    )
  }
}

@Composable
private fun FollowUpRow(
    person: HomeFollowUp,
    onOpenContact: (String) -> Unit,
    onRemind: () -> Unit,
) {
  val p = sadadPalette
  val late = person.lateDays != null
  val link = if (p.isDark) p.accent else p.primary
  val remindLabel = reminderLabel(person.name)
  Row(
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
        Modifier.weight(1f)
            .testTag(HomeTags.follow(person.contactId))
            .clickable { onOpenContact(person.contactId) }
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
          Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp)
              .clip(CircleShape)
              .background(p.soft)
              .padding(horizontal = 6.dp),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            initialsOf(person.name),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = link,
        )
      }
      Column(Modifier.weight(1f)) {
        Text(
            person.name,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
            color = p.text,
        )
        Text(
            text =
                if (late) lateLabel(person.lateDays ?: 0)
                else stringResource(R.string.home_follow_today),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = if (late) p.credit else p.muted,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(
            stringResource(R.string.home_follow_owes),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            color = p.owes,
        )
        MoneyText(
            cents = person.amount,
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
            color = p.owes,
        )
      }
    }
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(p.surface)
            .clickable(onClick = onRemind)
            .testTag(HomeTags.remind(person.contactId))
            .semantics {
              contentDescription = remindLabel
              role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
      WhatsAppGlyph(link)
    }
  }
}

/* ------------------------------------------------------------------ empty */

@Composable
private fun EmptyStart(onRecord: () -> Unit) {
  val p = sadadPalette
  val link = if (p.isDark) p.accent else p.primary
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Surface(
        color = p.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, p.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
          Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(p.soft),
            contentAlignment = Alignment.Center,
        ) {
          LedgerGlyph(link)
        }
        Text(
            stringResource(R.string.home_empty_card_title),
            fontSize = 21.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = p.text,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.home_empty_card_body),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            color = p.muted,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(p.primary)
                .clickable(onClick = onRecord)
                .testTag(HomeTags.EMPTY_CTA)
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
          Text(
              stringResource(R.string.home_empty_cta),
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White,
          )
        }
      }
    }
    Column(
        Modifier.testTag(HomeTags.AFTER_FIRST),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
          stringResource(R.string.home_after_first),
          fontSize = 15.sp,
          lineHeight = 22.sp,
          fontWeight = FontWeight.Bold,
          color = p.muted,
      )
      AfterRow(1, stringResource(R.string.home_after_1))
      AfterRow(2, stringResource(R.string.home_after_2))
      AfterRow(3, stringResource(R.string.home_after_3))
    }
  }
}

@Composable
private fun AfterRow(number: Int, text: String) {
  val p = sadadPalette
  val link = if (p.isDark) p.accent else p.primary
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
        Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp)
            .clip(CircleShape)
            .background(p.surface),
        contentAlignment = Alignment.Center,
    ) {
      Text("$number", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = link)
    }
    Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = p.text)
  }
}

/* ---------------------------------------------------------------- glyphs */

@Composable
private fun WhatsAppGlyph(color: Color) {
  Canvas(Modifier.size(20.dp)) {
    val stroke = size.minDimension * 0.09f
    drawCircle(
        color = color,
        radius = size.minDimension * 0.38f,
        center = Offset(size.width * 0.5f, size.height * 0.46f),
        style = Stroke(width = stroke),
    )
    val tail =
        Path().apply {
          moveTo(size.width * 0.32f, size.height * 0.72f)
          lineTo(size.width * 0.22f, size.height * 0.86f)
          lineTo(size.width * 0.46f, size.height * 0.78f)
          close()
        }
    drawPath(tail, color)
  }
}

@Composable
private fun LedgerGlyph(color: Color) {
  Canvas(Modifier.size(34.dp)) {
    val stroke = size.minDimension * 0.08f
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.16f, size.height * 0.12f),
        size = Size(size.width * 0.68f, size.height * 0.76f),
        cornerRadius = CornerRadius(size.width * 0.1f),
        style = Stroke(width = stroke),
    )
    drawLine(
        color,
        Offset(size.width * 0.32f, size.height * 0.12f),
        Offset(size.width * 0.32f, size.height * 0.88f),
        stroke,
    )
    drawLine(
        color,
        Offset(size.width * 0.44f, size.height * 0.38f),
        Offset(size.width * 0.7f, size.height * 0.38f),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(size.width * 0.44f, size.height * 0.58f),
        Offset(size.width * 0.7f, size.height * 0.58f),
        stroke,
        StrokeCap.Round,
    )
  }
}

/* ------------------------------------------------------------------ text */

/**
 * «إدخال واحد»، «إدخالان»، «3 إدخالات»، «11 إدخالًا» — the count picks the form, not the locale.
 */
internal fun entriesLabel(count: Int): String =
    when (count) {
      1 -> "إدخال واحد بانتظار الإرسال"
      2 -> "إدخالان بانتظار الإرسال"
      in 3..10 -> "$count إدخالات بانتظار الإرسال"
      else -> "$count إدخالًا بانتظار الإرسال"
    }

/** «دفعة واحدة»، «دفعتان»، «14 دفعة». */
internal fun paymentsLabel(count: Int): String =
    when (count) {
      1 -> "دفعة واحدة"
      2 -> "دفعتان"
      in 3..10 -> "$count دفعات"
      else -> "$count دفعة"
    }

/** «متأخر يوم واحد»، «متأخر يومين»، «متأخر 3 أيام»، «متأخر 15 يومًا». */
@Composable
private fun lateLabel(days: Int): String =
    when (days) {
      1 -> stringResource(R.string.home_late_one)
      2 -> stringResource(R.string.home_late_two)
      in 3..10 -> stringResource(R.string.home_late_few, days)
      else -> stringResource(R.string.home_late_many, days)
    }

@Composable
private fun reminderLabel(name: String): String = stringResource(R.string.home_remind, name)

/** «₪ 1,950.00», laid out left to right inside the Arabic line. */
private fun moneyText(cents: com.sadad.ledger.Cents): String = formatShekels(cents)

/** «8:00 م»: the time the offline window closes, in the phone's zone. */
internal fun clockTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

private fun openWhatsApp(context: Context, person: HomeFollowUp, storeName: String) {
  val body =
      "مرحبًا ${person.name}، معك $storeName. تذكير ودّي: عليك ${formatShekels(person.amount)}."
  runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=${Uri.encode(body)}"))
    )
  }
}

private fun initialsOf(name: String): String {
  val words = name.trim().split(' ').filter { it.isNotEmpty() }
  return when {
    words.size > 1 -> "${words[0].first()}${words[1].first()}"
    words.size == 1 -> words[0].take(2)
    else -> "؟"
  }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE، d MMMM", Locale.forLanguageTag("ar-u-nu-latn"))

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("ar-u-nu-latn"))

private val HeroInk = Color(0xFFC6F0E0)

private val HeroPanel = Color(0x1FC6F0E0)

private val HeroTrack = Color(0x38C6F0E0)

private val HeroBar = Color(0xFF78DEC0)

private val HeroShadow = Color(0x3802302B)

private val BadgeRed = Color(0xFFC13634)
