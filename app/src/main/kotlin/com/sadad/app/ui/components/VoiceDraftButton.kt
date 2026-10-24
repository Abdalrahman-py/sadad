package com.sadad.app.ui.components

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadPalette

/**
 * Launches the Arabic speech recognizer and hands the first result to [onText]. Hidden when the
 * device has no recognizer, like the Java app.
 */
@Composable
fun VoiceDraftButton(
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit = {},
) {
  val context = LocalContext.current
  val prompt = stringResource(R.string.voice_prompt)
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
        ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken =
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onText(spoken.trim())
      }
  val intent =
      remember(prompt) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
      }
  val available = remember(intent) { intent.resolveActivity(context.packageManager) != null }
  if (!available) return
  Column(
      modifier.fillMaxWidth().padding(vertical = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
        onClick = {
          onLaunch()
          launcher.launch(intent)
        },
        color = sadadPalette.soft,
        shape = CircleShape,
        modifier = Modifier.size(54.dp),
    ) {
      Icon(
          painterResource(R.drawable.ic_mic),
          contentDescription = stringResource(R.string.voice_draft),
          tint = if (sadadPalette.isDark) sadadPalette.text else sadadPalette.primary,
          modifier = Modifier.padding(14.dp),
      )
    }
    Text(
        stringResource(R.string.voice_review_hint),
        color = sadadPalette.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 10.dp),
    )
  }
}
