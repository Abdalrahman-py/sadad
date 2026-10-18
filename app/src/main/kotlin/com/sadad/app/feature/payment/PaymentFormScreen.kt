package com.sadad.app.feature.payment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun PaymentFormScreen(contactId: String, debtId: String?, modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.title_payment),
      modifier = modifier,
      detail = debtId?.let { "$contactId · $it" } ?: contactId,
  )
}
