package com.sadad.app.a11y

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.trash.TrashHidden
import com.sadad.app.feature.trash.TrashScreen
import com.sadad.app.feature.trash.TrashViewModel
import com.sadad.app.testing.STORE_A
import com.sadad.app.ui.UiTestApplication
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The trash with one archived Contact, at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class TrashA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  @get:Rule val temporary = TemporaryFolder()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  @Test fun trash() = trash(NORMAL_FONT)

  @Test fun trashAtLargestFont() = trash(LARGEST_FONT)

  private fun trash(fontScale: Float) = compose.withStore { fixture ->
    val store = checkNotNull(fixture.graph.current.value)
    awaiting {
      val id = store.repository.addContact("سامي", null, null, null, null).getOrThrow()
      fixture.settleAll()
      store.repository.archiveContact(id, "حذف الزبون من التطبيق").getOrThrow()
      fixture.settleAll()
    }
    val model =
        TrashViewModel(
                store.repository,
                store.outbox,
                DueReminders(File(temporary.root, "reminders.preferences_pb"), scope),
                TrashHidden(File(temporary.root, "trash.preferences_pb"), scope),
                STORE_A,
                context,
            )
            .also { fixture.closeWithStore(it.viewModelScope) }
    awaiting { model.state.first { state -> state.items.isNotEmpty() } }
    compose.showAt(fontScale) { TrashScreen(model) }
    compose.assertAccessible(hasText("سامي", substring = true))
  }
}
