package com.sadad.app.feature.quickadd

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for the same reason as ThemePreferenceTest: DataStore's file replace off-device.
@RunWith(RobolectricTestRunner::class)
class FrequentItemsTest {
  @get:Rule val temporary = TemporaryFolder()

  private val seed = listOf("حاجات", "كولا", "اندومي")

  @Test
  fun aFreshStoreHoldsTheSeedThenTheSavedListWinsAnEmptyOneIncluded() = runBlocking {
    val file = File(temporary.root, FrequentItems.FILE_NAME)
    val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val items = FrequentItems.create(first, file, seed)
      assertEquals(seed, items.items.first())

      items.save(listOf("خبز"))
      assertEquals(listOf("خبز"), items.items.first())

      items.save(emptyList())
      assertEquals(emptyList<String>(), items.items.first())
    } finally {
      first.coroutineContext[Job]?.cancelAndJoin()
    }
    // A restart reads the file again: the empty list survives and the seed does not come back.
    val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      assertEquals(emptyList<String>(), FrequentItems.create(second, file, seed).items.first())
    } finally {
      second.coroutineContext[Job]?.cancelAndJoin()
    }
  }

  @Test
  fun addTrimsAndRefusesBlankLongDuplicateAndThirteenthItems() {
    assertEquals(listOf("كولا", "خبز"), FrequentItems.add(listOf("كولا"), "  خبز "))
    assertNull(FrequentItems.add(listOf("كولا"), "   "))
    assertNull(FrequentItems.add(listOf("Cola"), "cola"))
    assertNull(FrequentItems.add(listOf("كولا"), " كولا "))
    assertNull(FrequentItems.add(emptyList(), "x".repeat(FrequentItems.MAX_CHARS + 1)))
    assertEquals(
        listOf("x".repeat(FrequentItems.MAX_CHARS)),
        FrequentItems.add(emptyList(), "x".repeat(FrequentItems.MAX_CHARS)),
    )
    val twelve = (1..12).map { "صنف $it" }
    assertEquals(12, FrequentItems.add(twelve.dropLast(1), "صنف 12")?.size)
    assertNull(FrequentItems.add(twelve, "صنف 13"))
  }
}
