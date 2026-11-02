package com.sadad.app.feature.contactpicker

import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.ledger.ContactView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The contacts list at its seam: what the people of the store agree the device's numbers mean, and
 * what picking one hands back. The device read and the people come from fakes; nothing here is a
 * Compose or permission test — those live on the screen and the a11y tests.
 */
class ContactPickerViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val people = MutableStateFlow(SadadPeople(active = emptyList(), archived = emptyList()))

  private val results = mutableListOf<PickedContact>()

  private fun model(vararg contacts: DeviceContact, granted: Boolean = false) =
      ContactPickerViewModel(
          device = FakeDeviceContacts(*contacts),
          people = people,
          grantedNow = { granted },
          onResult = { results.add(it) },
      )

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
  fun contactsWithoutANumberAreNotListed() = runTest {
    val model =
        model(DeviceContact("بدون رقم", emptyList()), DeviceContact("سارة", listOf("0599123456")))
    people.value = SadadPeople(emptyList(), emptyList())
    model.onPermissionResult(granted = true)

    advanceUntilIdle()

    assertEquals(listOf("سارة"), model.state.value.rows.map { it.name })
  }

  @Test
  fun theListIsSortedByArabicNameAndFoldsTheSearch() = runTest {
    val model =
        model(
            DeviceContact("سارة", listOf("0599123456")),
            DeviceContact("أحمد عبد", listOf("0501234567")),
            DeviceContact("نمر", listOf("0599111222")),
        )
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    assertEquals(listOf("أحمد عبد", "سارة", "نمر"), model.state.value.rows.map { it.name })

    model.onQueryChange("احمد")

    assertEquals(listOf("أحمد عبد"), model.state.value.rows.map { it.name })
    model.onQueryChange("")
    assertEquals(3, model.state.value.rows.size)
  }

  @Test
  fun aContactWithTwoNumbersAsksWhich() = runTest {
    val model = model(DeviceContact("وهبة", listOf("0599123457", "0299123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()

    model.onPick(row)

    assertEquals(row, model.state.value.chooser)
    assertTrue(results.isEmpty())
    model.onChoose(row.numbers.first { it.phone == null }) // the landline: name only

    assertEquals(listOf(PickedContact.New("وهبة", null, "970")), results)
  }

  @Test
  fun oneNumberIsPickedWithoutTheChooser() = runTest {
    val model = model(DeviceContact("سارة", listOf("0599123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()

    model.onPick(model.state.value.rows.single())

    assertTrue(model.state.value.chooser == null)
    assertEquals(listOf(PickedContact.New("سارة", "0599123456", "970")), results)
  }

  @Test
  fun aLandlineIsGreyedAndImportsTheNameOnly() = runTest {
    val model = model(DeviceContact("نمر", listOf("0299123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()

    assertEquals(NumberStatus.Invalid, row.numbers.single().status)
    model.onPick(row)

    assertEquals(listOf(PickedContact.New("نمر", null, "970")), results)
  }

  @Test
  fun aNumberTheStoreAlreadyHasReturnsThatPerson() = runTest {
    people.value =
        SadadPeople(active = listOf(person("p1", "سارة", "0599123456")), archived = emptyList())
    val model = model(DeviceContact("سارة", listOf("0599123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()

    assertEquals(NumberStatus.InLedger("p1"), row.numbers.single().status)
    model.onPick(row)

    assertEquals(listOf(PickedContact.Existing("p1")), results)
  }

  @Test
  fun aNumberInTheArchiveReturnsThatArchivedPerson() = runTest {
    people.value =
        SadadPeople(active = emptyList(), archived = listOf(person("p2", "سارة", "0599123456")))
    val model = model(DeviceContact("سارة", listOf("0599123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()

    val row = model.state.value.rows.single()
    assertEquals(NumberStatus.InArchive("p2"), row.numbers.single().status)
    model.onPick(row)

    assertEquals(listOf(PickedContact.Archived("p2")), results)
  }

  @Test
  fun aPlus972NumberCarriesItsCodeAndALocalOneDefaultsTo970() = runTest {
    val model =
        model(
            DeviceContact("مي", listOf("+972 599123456")),
            DeviceContact("فادي", listOf("0 599 123 450")),
        )
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val me = model.state.value.rows.single { it.name == "مي" }

    assertEquals("972", me.numbers.single().code)
    assertEquals("0599123456", me.numbers.single().phone)
    model.onPick(me)

    assertEquals(listOf(PickedContact.New("مي", "0599123456", "972")), results)
  }

  @Test
  fun aLocalNumberWithoutACodeDefaultsTo970() = runTest {
    val model = model(DeviceContact("فادي", listOf("0 599 123 450")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    model.onPick(model.state.value.rows.single())

    assertEquals(listOf(PickedContact.New("فادي", "0599123450", "970")), results)
  }

  @Test
  fun thePermissionStartsNotAskedAndTheDeviceIsNotRead() = runTest {
    val device = FakeDeviceContacts(DeviceContact("سارة", listOf("0599123456")))
    val model = ContactPickerViewModel(device, people, grantedNow = { false }) { results.add(it) }

    advanceUntilIdle()

    assertEquals(ContactPermission.NotAsked, model.state.value.permission)
    assertEquals(0, device.reads)
    assertTrue(model.state.value.rows.isEmpty())
  }

  @Test
  fun grantedPermissionLoadsTheList() = runTest {
    val device = FakeDeviceContacts(DeviceContact("سارة", listOf("0599123456")))
    val model = ContactPickerViewModel(device, people, grantedNow = { false }) { results.add(it) }
    model.onPermissionResult(granted = true)

    advanceUntilIdle()

    assertEquals(ContactPermission.Granted, model.state.value.permission)
    assertEquals(1, device.reads)
    assertEquals(listOf("سارة"), model.state.value.rows.map { it.name })
  }

  @Test
  fun aDeniedPermissionShowsTheReasonButNeverReadsTheDevice() = runTest {
    val device = FakeDeviceContacts(DeviceContact("سارة", listOf("0599123456")))
    val model = ContactPickerViewModel(device, people, grantedNow = { false }) { results.add(it) }
    model.onPermissionResult(granted = false, canAskAgain = true)

    advanceUntilIdle()

    assertEquals(ContactPermission.Denied, model.state.value.permission)
    assertEquals(0, device.reads)
  }

  @Test
  fun deniedForGoodRemembersItAndStillNeverReadsTheDevice() = runTest {
    val device = FakeDeviceContacts(DeviceContact("سارة", listOf("0599123456")))
    val model = ContactPickerViewModel(device, people, grantedNow = { false }) { results.add(it) }
    model.onPermissionResult(granted = false, canAskAgain = false)

    advanceUntilIdle()

    assertEquals(ContactPermission.DeniedForever, model.state.value.permission)
    assertEquals(0, device.reads)
  }

  @Test
  fun aPermissionGrantedBeforeThePickerOpenedShowsTheListAtOnce() = runTest {
    val device = FakeDeviceContacts(DeviceContact("سارة", listOf("0599123456")))
    val model = ContactPickerViewModel(device, people, grantedNow = { true }) { results.add(it) }

    advanceUntilIdle()

    assertEquals(ContactPermission.Granted, model.state.value.permission)
    assertEquals(listOf("سارة"), model.state.value.rows.map { it.name })
  }

  @Test
  fun theDismissedChooserAsksAgainOnTheNextTap() = runTest {
    val model = model(DeviceContact("وهبة", listOf("0599123457", "0299123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()
    model.onPick(row)
    model.dismissChooser()

    assertNull(model.state.value.chooser)
    model.onPick(row)

    assertEquals(row, model.state.value.chooser)
  }

  @Test
  fun aValidNumberInTheChooserImportsItsTenDigits() = runTest {
    val model = model(DeviceContact("وهبة", listOf("0299123456", "0599123457")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()
    model.onPick(row)
    model.onChoose(row.numbers.single { it.phone != null })

    assertEquals(listOf(PickedContact.New("وهبة", "0599123457", "970")), results)
  }

  @Test
  fun anActiveMatchInTwoNumbersIsReturnedUnlessTheOtherOneIsPicked() = runTest {
    people.value =
        SadadPeople(active = listOf(person("p1", "سارة", "0599123456")), archived = emptyList())
    val model = model(DeviceContact("سارة الجهاز", listOf("0599123456", "0599123450")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()
    model.onPick(row)
    model.onChoose(row.numbers.single { it.status is NumberStatus.Importable })

    assertEquals(listOf(PickedContact.New("سارة الجهاز", "0599123450", "970")), results)
  }

  @Test
  fun theChooserShowsTheNumbersTheOwnerWrote() = runTest {
    val model = model(DeviceContact("وهبة", listOf("+970599123457", "0299123456")))
    model.onPermissionResult(granted = true)
    advanceUntilIdle()
    val row = model.state.value.rows.single()
    model.onPick(row)

    assertEquals(
        listOf("+970599123457", "0299123456"),
        checkNotNull(model.state.value.chooser).numbers.map { it.raw },
    )
  }
}
