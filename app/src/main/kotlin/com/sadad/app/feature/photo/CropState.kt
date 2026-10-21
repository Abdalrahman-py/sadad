package com.sadad.app.feature.photo

/** Pixel bounds in the source after [CropState.rotation] has been applied. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * A square viewport expressed entirely in oriented source pixels, independent of screen density.
 */
class CropState(sourceWidth: Int, sourceHeight: Int) {
  init {
    require(sourceWidth > 0 && sourceHeight > 0)
  }

  var width: Int = sourceWidth
    private set

  var height: Int = sourceHeight
    private set

  var rotation: Int = 0
    private set

  private var zoomValue = 1f
  val zoom: Float
    get() = zoomValue

  private var offsetX = 0f
  private var offsetY = 0f

  fun setZoom(value: Float) {
    require(value.isFinite())
    zoomValue = value.coerceIn(1f, 4f)
    clamp()
  }

  /** The drag is the image's movement, so the viewport moves in the opposite direction. */
  fun drag(dx: Float, dy: Float) {
    require(dx.isFinite() && dy.isFinite())
    offsetX -= dx
    offsetY -= dy
    clamp()
  }

  fun rotate() {
    val previousWidth = width
    width = height
    height = previousWidth
    rotation = (rotation + 90) % 360
    center()
  }

  fun center() {
    zoomValue = 1f
    offsetX = 0f
    offsetY = 0f
  }

  fun exportRect(): CropRect {
    val side = minOf(width, height) / zoom
    val left = (width - side) / 2f + offsetX
    val top = (height - side) / 2f + offsetY
    return CropRect(left, top, left + side, top + side)
  }

  private fun clamp() {
    val side = minOf(width, height) / zoom
    val limitX = (width - side) / 2f
    val limitY = (height - side) / 2f
    offsetX = offsetX.coerceIn(-limitX, limitX)
    offsetY = offsetY.coerceIn(-limitY, limitY)
  }
}
