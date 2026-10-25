package com.sadad.app.feature.quickadd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.components.ScreenHeader

/**
 * Recording a debt or a payment, opened from «+» or from a contact's page. A placeholder until
 * ticket 81 builds it; [contactId], [payment] and [onOpenContact] are the routes it will use.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun QuickAddScreen(
    contactId: String?,
    payment: Boolean,
    onClose: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    ScreenHeader(
        stringResource(R.string.quickadd_title),
        actions = {
          TextButton(onClick = onClose) { Text(stringResource(R.string.quickadd_close)) }
        },
    )
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      Text(
          stringResource(R.string.quickadd_placeholder),
          style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}
