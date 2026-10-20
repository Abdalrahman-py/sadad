package com.sadad.app.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.export.StatementFormat
import com.sadad.app.ui.components.formatCents

/**
 * The reports screen's small building blocks: the banner, a total, a Contact row and the format
 * dialog.
 */
@Composable
internal fun Banner(text: String, error: Boolean) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
      color =
          if (error) MaterialTheme.colorScheme.onErrorContainer
          else MaterialTheme.colorScheme.onSecondaryContainer,
      modifier =
          Modifier.fillMaxWidth()
              .background(
                  if (error) MaterialTheme.colorScheme.errorContainer
                  else MaterialTheme.colorScheme.secondaryContainer
              )
              .padding(horizontal = 16.dp, vertical = 10.dp)
              .testTag(if (error) ReportsTags.ERROR else ReportsTags.SAVED),
  )
}

@Composable
internal fun Metric(tag: String, title: String, value: String, detail: String) {
  Card(
      modifier =
          Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(tag).semantics(
              mergeDescendants = true
          ) {},
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = title,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          text = value,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
      )
      Text(
          text = detail,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun PersonRow(
    person: ReportPerson,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
) {
  Card(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
      Text(
          text = person.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
      )
      Text(
          text = stringResource(R.string.reports_person_balance, formatCents(person.openBalance)),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onOpen(person.contactId) },
            enabled = enabled,
            modifier = Modifier.testTag(ReportsTags.personOpen(person.contactId)),
        ) {
          Text(stringResource(R.string.reports_open_statement))
        }
        TextButton(
            onClick = { onShare(person.contactId) },
            enabled = enabled,
            modifier = Modifier.testTag(ReportsTags.personShare(person.contactId)),
        ) {
          Text(stringResource(R.string.reports_share_statement))
        }
      }
    }
  }
}

@Composable
internal fun FormatDialog(onDismiss: () -> Unit, onChoose: (StatementFormat) -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.reports_format_title)) },
      text = { Text(stringResource(R.string.reports_export_detail)) },
      confirmButton = {
        TextButton(onClick = { onChoose(StatementFormat.PDF) }) {
          Text(stringResource(R.string.reports_format_pdf))
        }
      },
      dismissButton = {
        Row {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.reports_cancel)) }
          TextButton(onClick = { onChoose(StatementFormat.XLSX) }) {
            Text(stringResource(R.string.reports_format_xlsx))
          }
        }
      },
  )
}
