package com.sadad.app.feature.contacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.ScreenTag
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun ContactsScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.title_contacts),
      modifier = modifier,
      tag = ScreenTag.CONTACTS,
  )
}

@Composable
fun ContactDetailScreen(contactId: String, modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.title_contact_detail),
      modifier = modifier,
      tag = ScreenTag.CONTACT_DETAIL,
      detail = stringResource(R.string.contact_detail_id, contactId),
  )
}
