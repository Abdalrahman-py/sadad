package com.sadad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.Cents

enum class BalanceTone {
  OWES,
  CREDIT,
  SETTLED,
}

/**
 * A balance said from the owner's side: [word] «عليه / له / مسدد», the [tone] that colours it, the
 * [amount] in [formatShekels] (null when settled) and the [spoken] text a screen reader hears.
 */
data class BalanceWordText(
    val word: String,
    val tone: BalanceTone,
    val amount: String?,
    val spoken: String,
)

/** [netCents] `> 0` means the person owes the store. */
fun balanceWord(netCents: Long): BalanceWordText {
  if (netCents == 0L) return BalanceWordText("مسدد", BalanceTone.SETTLED, null, "مسدد")
  val word = if (netCents > 0) "عليه" else "له"
  val size = Cents(Math.abs(netCents))
  return BalanceWordText(
      word = word,
      tone = if (netCents > 0) BalanceTone.OWES else BalanceTone.CREDIT,
      amount = formatShekels(size),
      spoken = "$word ${spokenAmount(size)} شيكل",
  )
}

/**
 * The amount in its tone's colour; the row's [EntrySign] carries the ±. TalkBack hears the word.
 */
@Composable
fun BalanceWord(netCents: Long, modifier: Modifier = Modifier) {
  BalanceWord(balanceWord(netCents), modifier)
}

/** Ready-made [text]: the row already formatted its amount once, so nothing is formatted here. */
@Composable
fun BalanceWord(text: BalanceWordText, modifier: Modifier = Modifier) {
  val color = toneColor(text.tone)
  val semantics = modifier.clearAndSetSemantics { contentDescription = text.spoken }
  val amount = text.amount
  if (amount == null) {
    Text(
        text.word,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = semantics,
    )
  } else {
    MoneyText(
        amount,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = semantics,
    )
  }
}

/** Minus is red (they owe, or a debt); plus is green (the store holds theirs, or a payment). */
@Composable
fun toneColor(tone: BalanceTone): Color {
  val p = sadadPalette
  return when (tone) {
    BalanceTone.OWES -> p.owes
    BalanceTone.CREDIT -> if (p.isDark) p.accent else p.primary
    BalanceTone.SETTLED -> p.muted
  }
}

/**
 * The 40dp circle at the start of every person and entry row: − on red for a debt or what they owe,
 * + on green for a payment or what the store holds, a check when settled. Decorative; the row's
 *   text says it.
 */
@Composable
fun EntrySign(tone: BalanceTone, modifier: Modifier = Modifier) {
  val p = sadadPalette
  val (fill, glyph) =
      when (tone) {
        BalanceTone.OWES -> p.owesSoft to R.drawable.ic_sign_minus
        BalanceTone.CREDIT -> p.soft to R.drawable.ic_sign_plus
        BalanceTone.SETTLED -> p.surface to R.drawable.ic_check
      }
  Box(
      modifier.size(40.dp).background(fill, CircleShape).clearAndSetSemantics {},
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painterResource(glyph),
        contentDescription = null,
        tint = toneColor(tone),
        modifier = Modifier.size(24.dp),
    )
  }
}
