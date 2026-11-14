package com.sadad.app.feature.contactpicker

import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.ledger.ContactView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The one contact the system picker returns, against the store's people: a new person, someone the
 * store keeps, or someone in its archive. The picker itself is Android's; nothing here reads the
 * address book.
 */
class ContactPickerViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val people = MutableStateFlow(SadadPeople(active = emptyList(), archived = emptyList()))

  /** A store person as the ledger projects it, for the comparison side of the tests. */
  private fun person(id: String, name: String, phone: String) =
      ContactView(
          id = id,
          name = name,
          phone = phone,
          kind = com.sadad.ledger.ContactKind.CUSTOMER,
          category = "",
          note = "",
          version = 0,
          pending = false,
          createdAt = java.time.Instant.EPOCH,
          createdBy = "",
          operationId = "",
          whatsappCode = "970",
      )

  @Test
  fun aNewNumberIsHandedOnceAndTheScreenConsumesIt() = runTest {
    val model = ContactPickerViewModel(people)
    model.onDeviceContact(DeviceContact("سارة", listOf("0599123456")))
    advanceUntilIdle()

    assertEquals(PickedContact.New("سارة", "0599123456", "970"), model.state.value.picked)
    model.pickDelivered()
    assertNull(model.state.value.picked)
  }

  @Test
  fun aLandlineImportsTheNameOnly() = runTest {
    val model = ContactPickerViewModel(people)
    model.onDeviceContact(DeviceContact("نمر", listOf("0299123456")))
    advanceUntilIdle()

    assertEquals(PickedContact.New("نمر", null, "970"), model.state.value.picked)
  }

  @Test
  fun aNumberTheStoreAlreadyHasReturnsThatPerson() = runTest {
    people.value =
        SadadPeople(active = listOf(person("p1", "سارة", "0599123456")), archived = emptyList())
    val model = ContactPickerViewModel(people)
    model.onDeviceContact(DeviceContact("سارة", listOf("059 912 3456")))
    advanceUntilIdle()

    assertEquals(PickedContact.Existing("p1"), model.state.value.picked)
  }

  @Test
  fun aNumberInTheArchiveReturnsThatArchivedPerson() = runTest {
    people.value =
        SadadPeople(active = emptyList(), archived = listOf(person("p2", "سارة", "0599123456")))
    val model = ContactPickerViewModel(people)
    model.onDeviceContact(DeviceContact("سارة", listOf("0599123456")))
    advanceUntilIdle()

    assertEquals(PickedContact.Archived("p2"), model.state.value.picked)
  }

  @Test
  fun aPlus972NumberCarriesItsCodeAndALocalOneDefaultsTo970() = runTest {
    val first = ContactPickerViewModel(people)
    first.onDeviceContact(DeviceContact("مي", listOf("+972 599123456")))
    val second = ContactPickerViewModel(people)
    second.onDeviceContact(DeviceContact("فادي", listOf("0 599 123 450")))
    advanceUntilIdle()

    assertEquals(PickedContact.New("مي", "0599123456", "972"), first.state.value.picked)
    assertEquals(PickedContact.New("فادي", "0599123450", "970"), second.state.value.picked)
  }
}
