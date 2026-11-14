package dev.sponsorblock.companion.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import dev.sponsorblock.companion.model.Category

/**
 * A full-screen, non-interactive overlay that paints one rounded bar per SponsorBlock segment on top
 * of YouTube's seekbar. The window covers the whole display, so [barBounds] (from the seekbar's
 * getBoundsInScreen) already share the canvas coordinate space — we just draw where told.
 *
 * Drawing is intentionally cheap: a handful of rects on each invalidate, only when the seekbar is
 * actually visible. When [barBounds] is null (controls hidden / not a watch page) nothing is drawn.
 */
class SeekbarOverlayView(
  context: Context
) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rect = RectF()
  private val loc = IntArray(2)
  private val density = resources.displayMetrics.density

  /** Screen-space bounds of the YouTube seekbar, or null to draw nothing. */
  private var barBounds: Rect? = null

  /** Moves the bars to the seekbar bounds, or hides them when null. */
  fun update(bounds: Rect?) {
    if (bounds == barBounds) {
      return
    }
    barBounds = bounds?.let { Rect(it) }
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    val b = barBounds ?: return
    val dur = OverlayState.durationSec
    if (dur <= 0.0 || b.width() <= 0) {
      return
    }

    // barBounds are ABSOLUTE screen coordinates (getBoundsInScreen), but our overlay window may be
    // inset from the screen origin (e.g. it starts below the status bar in portrait, so the window's
    // top is at screen y=173). Convert to view-local coordinates by subtracting the view's on-screen
    // location, or every bar lands offset by the inset (the "bars below the seekbar" bug).
    getLocationOnScreen(loc)
    val offX = loc[0].toFloat()
    val offY = loc[1].toFloat()

    // Where to put the thin strip vertically. A thin bar node → center it. A tall node is a
    // full-player container (portrait's watch_while_time_bar_view*) whose track sits at the BOTTOM
    // edge, so anchor there instead of painting across the middle of the video.
    val thickness = (BAR_DP * density).coerceAtMost(b.height().toFloat())
    val tall = b.height() > TALL_NODE_DP * density
    val cy =
      (
        if (tall) {
          b.bottom - thickness / 2f
        } else {
          b.exactCenterY()
        }
      ) - offY
    val top = cy - thickness / 2f
    val bottom = cy + thickness / 2f
    val radius = thickness / 2f
    val left = b.left.toFloat() - offX
    val widthPx = b.width().toFloat()

    for (seg in OverlayState.segments) {
      val cat = Category.byId(seg.category) ?: continue
      val startF = (seg.start / dur).coerceIn(0.0, 1.0)
      val endF = (seg.end / dur).coerceIn(0.0, 1.0)
      var x0 = left + (startF * widthPx).toFloat()
      var x1 = left + (endF * widthPx).toFloat()
      // Give a zero/near-zero-width segment (e.g. a POI highlight) a visible minimum.
      if (x1 - x0 < MIN_BAR_PX) {
        x1 = x0 + MIN_BAR_PX
      }
      rect.set(x0, top, x1, bottom)
      paint.color = withOpacity(cat.color.toInt(), OPACITY)
      canvas.drawRoundRect(rect, radius, radius, paint)
    }
  }

  private fun withOpacity(
    argb: Int,
    opacity: Float
  ): Int {
    val a = (opacity * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0x00FFFFFF)
  }

  /** Drawing constants. */
  companion object {
    private const val BAR_DP = 4f

    // Above this node height, treat bounds as a full-player container and pin the bar to its bottom.
    private const val TALL_NODE_DP = 72f
    private const val MIN_BAR_PX = 6f
    private const val OPACITY = 0.85f // extension paints the bar near-opaque over the track
  }
}
