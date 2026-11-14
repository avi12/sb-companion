package dev.sponsorblock.companion.overlay

import dev.sponsorblock.companion.model.Segment

/**
 * Bridges the notification-listener side (which fetches segments + knows the video duration) to the
 * accessibility service that paints them on YouTube's seekbar. Both live in the same process, so a
 * plain singleton is enough; [onChange] lets the overlay invalidate as soon as new data lands.
 */
object OverlayState {
  @Volatile
  var segments: List<Segment> = emptyList()
    private set

  /** Video duration in SECONDS (0 until known). Bars are positioned as start/duration. */
  @Volatile
  var durationSec: Double = 0.0
    private set

  @Volatile
  var onChange: (() -> Unit)? = null

  /** Replaces the shared segments and duration and notifies the overlay. */
  fun publish(
    segments: List<Segment>,
    durationSec: Double
  ) {
    this.segments = segments
    this.durationSec = durationSec
    onChange?.invoke()
  }

  /** Drops the shared segments and notifies the overlay. */
  fun clear() {
    if (segments.isEmpty() && durationSec == 0.0) {
      return
    }
    segments = emptyList()
    durationSec = 0.0
    onChange?.invoke()
  }
}
