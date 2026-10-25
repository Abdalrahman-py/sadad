package com.sadad.app.feature.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contacts.contact
import com.sadad.app.feature.contacts.contactId
import com.sadad.app.feature.contacts.debt
import com.sadad.app.feature.contacts.seedCommands
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContactsViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun searchingByNameKeepsOnlyNamesThatContainTheText() = runTest {
    TestGraph.signedIn().use { fixture ->
      val names = (0 until 5000).map { "شخص $it" }.toMutableList()
      listOf("سامي", "سالم", "سامر", "مساء", "سارة").forEachIndexed { index, name ->
        names[index] = name
      }
      fixture.seedCommands(
          names.mapIndexed { index, name -> contact(name, contactId(index), minute = index) }
      )
      val model =
          ContactsViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.registered == 5000 }

      model.onQueryChange("سا")

      val state = model.state.first { it.query == "سا" && it.loaded }
      assertEquals(5, state.rows.size)
      assertTrue(state.rows.all { it.name.contains("سا") })
    }
  }

  @Test
  fun searchingArabicIndicPhoneDigitsMatchesTheStoredPhone() = runTest {
    TestGraph.signedIn().use { fixture ->
      val phone = contact("سامي", contactId(0), phone = "+970599123456", minute = 0)
      fixture.seedCommands(
          listOf(phone) + (1 until 5000).map { contact("شخص $it", contactId(it), minute = it) }
      )
      val model =
          ContactsViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.registered == 5000 }

      model.onQueryChange("٠٥٩٩")

      val state = model.state.first { it.query == "٠٥٩٩" && it.loaded }
      assertEquals("سامي", state.rows.single().name)
      assertEquals("+970599123456", state.rows.single().phone)
    }
  }

  @Test
  fun canonicalAndFormattedPhoneQueriesMatchWithoutTurningNamesIntoPhoneQueries() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(0), phone = "+970599123456")
      fixture.seedCommands(listOf(person, contact("خالد", contactId(1))))
      val model =
          ContactsViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.registered == 2 }
      for (query in listOf("+970599123456", "+٩٧٠ (٥٩٩) ١٢٣-٤٥٦", "شخص 599", "+")) {
        model.onQueryChange(query)
        val state = model.state.first { it.loaded && it.query == query }
        assertEquals(
            if (query.startsWith("+970") || query.startsWith("+٩٧٠")) listOf(person.entityId)
            else emptyList<String>(),
            state.rows.map { it.id },
        )
      }
    }
  }

  @Test
  fun highestDebtSortPutsTheLargestOpenBalanceFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      val small = contact("صغير", contactId(1), minute = 0)
      val large = contact("كبير", contactId(2), minute = 1)
      val settled = contact("بلا دين", contactId(3), minute = 2)
      fixture.seedCommands(
          listOf(
              small,
              debt(small.entityId, amount = 9_000, minute = 3),
              large,
              debt(large.entityId, amount = 12_500, minute = 4),
              settled,
          )
      )
      val model =
          ContactsViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.rows.size == 3 }

      model.onSortChange(ContactSort.HIGHEST_DEBT)

      val state = model.state.first { it.sort == ContactSort.HIGHEST_DEBT && it.rows.size == 3 }
      assertEquals(listOf("كبير", "صغير", "بلا دين"), state.rows.map { it.name })
      assertEquals(Cents(12_500), state.rows.first().balance)
      assertEquals(Cents.ZERO, state.rows.last().balance)
    }
  }

  @Test
  fun newestSortPutsTheLatestCreatedContactFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      val first = contact("الأول", contactId(1), minute = 0)
      val second = contact("الثاني", contactId(2), minute = 1)
      val third = contact("الثالث", contactId(3), minute = 2)
      fixture.seedCommands(listOf(first, second, third))
      val model =
          ContactsViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.rows.size == 3 }

      model.onSortChange(ContactSort.NEWEST)

      val state = model.state.first { it.sort == ContactSort.NEWEST && it.rows.size == 3 }
      assertEquals(listOf("الثالث", "الثاني", "الأول"), state.rows.map { it.name })
    }
  }

  @Test
  fun searchAndSortSurviveARecreatedViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = contact("سامي", contactId(1), minute = 0)
      val salem = contact("سالم", contactId(2), minute = 1)
      val khaled = contact("خالد", contactId(3), minute = 2)
      fixture.seedCommands(
          listOf(
              sami,
              debt(sami.entityId, amount = 9_000, minute = 3),
              salem,
              debt(salem.entityId, amount = 12_000, minute = 4),
              khaled,
              debt(khaled.entityId, amount = 5_000, minute = 5),
          )
      )
      val saved = SavedStateHandle()
      val first =
          ContactsViewModel(fixture.graph, saved).also { fixture.closeWithStore(it.viewModelScope) }
      first.state.first { it.loaded && it.rows.size == 3 }
      first.onQueryChange("س")
      first.onSortChange(ContactSort.HIGHEST_DEBT)
      first.state.first {
        it.query == "س" && it.sort == ContactSort.HIGHEST_DEBT && it.rows.size == 2
      }

      val second =
          ContactsViewModel(fixture.graph, saved).also { fixture.closeWithStore(it.viewModelScope) }

      val state = second.state.first { it.loaded && it.rows.size == 2 }
      assertEquals("س", state.query)
      assertEquals(ContactSort.HIGHEST_DEBT, state.sort)
      assertEquals(listOf("سالم", "سامي"), state.rows.map { it.name })
    }
  }
}
