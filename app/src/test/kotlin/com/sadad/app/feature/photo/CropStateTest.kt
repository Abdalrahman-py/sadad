package com.sadad.app.feature.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropStateTest {
  @Test
  fun theVisibleSquareMapsBackToSourcePixelsAtEachZoom() {
    val crop = CropState(4000, 3000)
    assertEquals(CropRect(500f, 0f, 3500f, 3000f), crop.exportRect())
    crop.setZoom(2f)
    assertEquals(CropRect(1250f, 750f, 2750f, 2250f), crop.exportRect())
    crop.drag(100_000f, -100_000f)
    val edge = crop.exportRect()
    assertEquals(1500f, edge.right - edge.left, 0.001f)
    assertTrue(edge.left >= 0f && edge.right <= 4000f)
    assertTrue(edge.top >= 0f && edge.bottom <= 3000f)
    crop.rotate()
    assertEquals(3000, crop.width)
    assertEquals(4000, crop.height)
    crop.center()
    assertEquals(1f, crop.zoom, 0f)
    assertEquals(CropRect(0f, 500f, 3000f, 3500f), crop.exportRect())
    crop.setZoom(0.1f)
    assertEquals(1f, crop.zoom, 0f)
    crop.setZoom(10f)
    assertEquals(4f, crop.zoom, 0f)
  }
}
