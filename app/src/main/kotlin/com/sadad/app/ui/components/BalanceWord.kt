package com.sadad.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
  val amount = formatShekels(Cents(Math.abs(netCents)))
  // Read as words: no «₪» symbol, no minus, trailing zero cents dropped.
  val spokenAmount = amount.removePrefix("₪ ")
  return BalanceWordText(
      word = word,
      tone = if (netCents > 0) BalanceTone.OWES else BalanceTone.CREDIT,
      amount = amount,
      spoken = "$word $spokenAmount شيكل",
  )
}

/** The word over an arrow and the amount; never colour alone. */
@Composable
fun BalanceWord(netCents: Long, modifier: Modifier = Modifier) {
  val text = balanceWord(netCents)
  val p = sadadPalette
  val color =
      when (text.tone) {
        BalanceTone.OWES -> p.owes
        BalanceTone.CREDIT -> p.credit
        BalanceTone.SETTLED -> p.muted
      }
  Column(
      modifier = modifier.clearAndSetSemantics { contentDescription = text.spoken },
      horizontalAlignment = Alignment.End,
  ) {
    Text(
        text.word,
        color = color,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
    )
    if (text.amount == null) return@Column
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Icon(
          painterResource(
              if (text.tone == BalanceTone.OWES) R.drawable.ic_balance_owes
              else R.drawable.ic_balance_credit
          ),
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(14.dp),
      )
      MoneyText(
          Cents(Math.abs(netCents)),
          style = MaterialTheme.typography.titleSmall,
          color = color,
      )
    }
  }
}
