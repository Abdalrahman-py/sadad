package com.sadad.app.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class LastSyncRowTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun showsNeverThenTheLocalDateAndTimeEvenWhileOffline() {
    val last = mutableStateOf<Long?>(null)
    compose.setContent {
      SadadTheme {
        LazyColumn {
          devicesSection(
              DevicesSlice(sync = SyncLine.Offline, lastSyncAt = last.value),
              SettingsNavigation(),
          )
        }
      }
    }
    compose.onNodeWithText("لم تتم مزامنة ناجحة بعد").assertIsDisplayed()
    compose.runOnIdle {
      last.value =
          LocalDateTime.of(2026, 11, 1, 14, 35, 22)
              .atZone(ZoneId.systemDefault())
              .toInstant()
              .toEpochMilli()
    }
    compose.onNodeWithText("آخر مزامنة ناجحة: 01/11/2026 · 14:35:22").assertIsDisplayed()
  }
}
