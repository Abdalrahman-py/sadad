package com.sadad.app.feature.quickadd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.ui.components.SadadTextField
import com.sadad.app.ui.theme.sadadPalette

object FrequentItemsTags {
  const val EDITOR = "frequent-items:editor"
  const val FIELD = "frequent-items:field"
  const val ADD = "frequent-items:add"
  const val SAVE = "frequent-items:save"

  fun remove(index: Int) = "frequent-items:remove:$index"

  fun up(index: Int) = "frequent-items:up:$index"

  fun down(index: Int) = "frequent-items:down:$index"
}

/**
 * «الأصناف المتكررة»: add, remove and reorder the note dialog's chips; nothing is kept until «حفظ».
 */
@Composable
internal fun FrequentItemsEditor(
    initial: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
  val p = sadadPalette
  var items by remember { mutableStateOf(initial) }
  var typed by remember { mutableStateOf("") }
  val added = FrequentItems.add(items, typed)
  AlertDialog(
      modifier = Modifier.testTag(FrequentItemsTags.EDITOR),
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.quickadd_items_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            SadadTextField(
                value = typed,
                onValueChange = { typed = it.take(FrequentItems.MAX_CHARS) },
                placeholder = stringResource(R.string.quickadd_items_hint),
                modifier = Modifier.weight(1f).testTag(FrequentItemsTags.FIELD),
            )
            TextButton(
                enabled = added != null,
                onClick = {
                  items = added ?: items
                  typed = ""
                },
                modifier = Modifier.testTag(FrequentItemsTags.ADD),
            ) {
              Text(stringResource(R.string.quickadd_items_add))
            }
          }
          if (items.size >= FrequentItems.MAX_ITEMS) {
            Text(
                stringResource(R.string.quickadd_items_full, FrequentItems.MAX_ITEMS),
                color = p.muted,
            )
          }
          LazyColumn(Modifier.heightIn(max = 320.dp)) {
            itemsIndexed(items) { index, item ->
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item, color = p.text, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = index > 0,
                    onClick = { items = items.swap(index, index - 1) },
                    modifier = Modifier.testTag(FrequentItemsTags.up(index)),
                ) {
                  Text(stringResource(R.string.quickadd_items_up))
                }
                TextButton(
                    enabled = index < items.lastIndex,
                    onClick = { items = items.swap(index, index + 1) },
                    modifier = Modifier.testTag(FrequentItemsTags.down(index)),
                ) {
                  Text(stringResource(R.string.quickadd_items_down))
                }
                TextButton(
                    onClick = { items = items.filterIndexed { i, _ -> i != index } },
                    modifier = Modifier.testTag(FrequentItemsTags.remove(index)),
                ) {
                  Text(stringResource(R.string.quickadd_items_remove))
                }
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = { onSave(items) },
            modifier = Modifier.testTag(FrequentItemsTags.SAVE),
        ) {
          Text(stringResource(R.string.quickadd_note_save))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.quickadd_cancel)) }
      },
  )
}

private fun List<String>.swap(a: Int, b: Int): List<String> =
    toMutableList().also {
      it[a] = this[b]
      it[b] = this[a]
    }
