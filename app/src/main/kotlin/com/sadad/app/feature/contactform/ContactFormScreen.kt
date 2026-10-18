package com.sadad.app.feature.contactform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun ContactFormScreen(contactId: String?, modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.title_contact_form),
      modifier = modifier,
      detail = contactId,
  )
}
