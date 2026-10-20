package com.sadad.app.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.export.MovementKind
import com.sadad.app.ui.ScreenTag
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.PendingBadge
import com.sadad.app.ui.components.formatCents
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenContact: (String) -> Unit = {},
    viewModel: HomeViewModel = homeViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(viewModel, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.followMonth() }
  }
  HomeContent(state = state, onOpenContact = onOpenContact, modifier = modifier)
}

@Composable
private fun homeViewModel(): HomeViewModel =
    viewModel(
        factory =
            viewModelFactory {
              initializer { HomeViewModel((this[APPLICATION_KEY] as SadadApplication).graph) }
            }
    )

@Composable
fun HomeContent(
    state: HomeUiState,
    onOpenContact: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
  val storeName = state.storeName.ifBlank { stringResource(R.string.home_default_store) }
  var historyOpen by rememberSaveable { mutableStateOf(false) }
  val debtColor = if (isSystemInDarkTheme()) Color(0xFFF59A9F) else Color(0xFFAB323A)
  val paymentColor = MaterialTheme.colorScheme.secondary

  Column(
      modifier =
          modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 18.dp)
              .testTag(ScreenTag.HOME),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
        text = stringResource(R.string.home_greeting, storeName),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.home_total_debts),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.home_from_customers),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFC6F0E0),
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = formatCents(state.totalReceivable),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.testTag(HomeTags.TOTAL),
        )
      }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
          text = stringResource(R.string.home_month_heading),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
      )
      Text(
          text = MONTH_FORMAT.format(state.month.atDay(1)),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      MonthMetric(
          title = stringResource(R.string.home_month_debts),
          value = state.monthDebts,
          ratio = state.debtsRatio,
          color = debtColor,
          modifier = Modifier.weight(1f),
      )
      MonthMetric(
          title = stringResource(R.string.home_month_payments),
          value = state.monthPayments,
          ratio = state.paymentsRatio,
          color = paymentColor,
          modifier = Modifier.weight(1f),
      )
    }
    TextButton(onClick = { historyOpen = !historyOpen }) {
      Text(
          stringResource(
              if (historyOpen) R.string.home_hide_history else R.string.home_show_history
          )
      )
    }
    if (historyOpen) {
      if (state.recent.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.home_empty_title),
            detail = stringResource(R.string.home_empty_detail),
            modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for (movement in state.recent) {
            MovementRow(
                movement = movement,
                debtColor = debtColor,
                paymentColor = paymentColor,
                onOpenContact = onOpenContact,
            )
          }
        }
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun MonthMetric(
    title: String,
    value: Cents,
    ratio: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
  Card(
      modifier = modifier,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(15.dp).padding(bottom = 10.dp)) {
      Text(
          text = title,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          text = formatCents(value),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = color,
          modifier = Modifier.padding(top = 5.dp),
      )
      MonthlyProgressBar(
          ratio = ratio,
          color = color,
          modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

/** The Java view: a 4 dp rounded track at 30/255 alpha, filled from the right in 650 ms. */
@Composable
fun MonthlyProgressBar(ratio: Float, color: Color, modifier: Modifier = Modifier) {
  val clamped = ratio.coerceIn(0f, 1f)
  val progress = remember { Animatable(0f) }
  LaunchedEffect(clamped) {
    progress.animateTo(clamped, tween(durationMillis = 650, easing = DECELERATE))
  }
  val description = stringResource(R.string.home_month_ratio, (clamped * 100).roundToInt())

  Canvas(
      modifier = modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = description }
  ) {
    val radius = size.height / 2f
    drawRoundRect(
        color = color.copy(alpha = 30f / 255f),
        size = size,
        cornerRadius = CornerRadius(radius),
    )
    if (progress.value <= 0f) return@Canvas
    val width = max(size.width * progress.value, min(size.width, 5.dp.toPx()))
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width, 0f),
        size = Size(width, size.height),
        cornerRadius = CornerRadius(radius),
    )
  }
}

@Composable
private fun MovementRow(
    movement: HomeMovement,
    debtColor: Color,
    paymentColor: Color,
    onOpenContact: (String) -> Unit,
) {
  val payment = movement.kind == MovementKind.PAYMENT
  val receivable = movement.direction == Direction.RECEIVABLE
  val incoming = if (receivable) payment else !payment
  val color = if (incoming) paymentColor else debtColor
  val kind =
      when {
        payment && !receivable -> R.string.home_payment_sent_kind
        payment -> R.string.home_payment_kind
        !receivable -> R.string.home_debt_payable_kind
        else -> R.string.home_debt_kind
      }
  Card(
      onClick = { onOpenContact(movement.contactId) },
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            text = movement.contactName.ifBlank { stringResource(R.string.home_unknown_contact) },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                stringResource(
                    R.string.home_movement_meta,
                    stringResource(kind),
                    TIME_FORMAT.withZone(ZoneId.systemDefault()).format(movement.createdAt),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          text =
              stringResource(
                  if (incoming) R.string.home_incoming_amount else R.string.home_outgoing_amount,
                  formatCents(movement.amount),
              ),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = color,
      )
      if (movement.pending) PendingBadge()
    }
  }
}

private val DECELERATE = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("ar"))

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

object HomeTags {
  const val TOTAL = "home:total"
}
