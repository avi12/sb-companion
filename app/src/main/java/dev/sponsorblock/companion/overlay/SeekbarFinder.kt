package dev.sponsorblock.companion.overlay

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/** Locates the player scrubber in YouTube's accessibility tree. */
internal object SeekbarFinder {
  private class Scan {
    var best: Rect? = null
    var bestQuality = 0

    // Only paint when the player controls are actually up. YouTube keeps a persistent thin progress
    // line (and its container node) even after the controls auto-hide, so a bar node alone isn't
    // enough — we require a controls-only marker (the elapsed-time label / play-pause button) to be
    // present, and hide the overlay the moment it's gone.
    var areControlsVisible = false

    // Some current YT Music fullscreen variants expose only the progress SeekBar and omit both
    // the elapsed-time/play-pause nodes and the named container from accessibility, even while
    // their controls are visibly on screen. Its "current of total" description is specific enough
    // to trust in that case.
    var hasYtMusicTimeBar = false
  }

  /**
   * Breadth-first search for the player scrubber, preferring the most reliable signal.
   *
   * Match quality (from reverse-engineering YouTube 21.26.364, see
   * docs/youtube-seekbar-reversing.md): an exact time-bar resource-id beats a "TimeBar" class name,
   * which beats a bare RangeInfo node. Within a quality tier we keep the SHORTEST node — in portrait
   * the only exposed time-bar node is a full-height player container (`watch_while_time_bar_view*`),
   * so we accept tall id-matched nodes and let the overlay view anchor the strip to their bottom
   * edge (where the track actually sits). We stop early once we have an id-level match.
   */
  fun find(
    root: AccessibilityNodeInfo,
    density: Float
  ): Rect? {
    // AccessibilityNodeInfo.parent is unexpectedly null for YT Music's SeekBar on recent builds.
    // Keep the relationship while walking instead of asking the node to reconstruct it later.
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>>()
    queue.add(root to null)
    val scan = Scan()
    val labelGapPx = (YT_MUSIC_LABEL_GAP_DP * density).toInt()
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_NODES) {
      visited++
      val (node, parent) = queue.removeFirst()
      inspect(node, parent, labelGapPx, scan)
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { queue.add(it to node) }
      }
    }
    val isPlayerVisible = scan.areControlsVisible || scan.hasYtMusicTimeBar
    return if (isPlayerVisible) scan.best else null
  }

  private fun inspect(
    node: AccessibilityNodeInfo,
    parent: AccessibilityNodeInfo?,
    labelGapPx: Int,
    scan: Scan
  ) {
    val id = node.viewIdResourceName
    if (id != null && CONTROLS_MARKER_SUFFIXES.any { id.endsWith(it) }) {
      scan.areControlsVisible = true
    }
    val quality = seekbarQuality(node, parent)
    if (quality == QUALITY_NONE) {
      return
    }
    val bounds = Rect().also { node.getBoundsInScreen(it) }
    // YT Music exposes the scrubber as a bare `android.widget.SeekBar` with NO id. Its
    // accessibility bounds cover the entire controls row, including the elapsed and total-time
    // labels. The wrapping `time_bar_container` is a better starting point, but it includes
    // those labels too. Trim between the label edges to get the drawable track itself (see
    // docs/ytmusic-seekbar-reversing.md).
    if (quality == QUALITY_SEEKBAR && id.isNullOrEmpty()) {
      trimYtMusicTrack(node, parent, bounds, labelGapPx, scan)
    }
    // An exact id is trusted on its own; weaker signals (TimeBar class / RangeInfo)
    // must be wide-and-short so we don't grab a volume or settings slider.
    val isWideEnough = bounds.width() > MIN_BAR_WIDTH_PX
    val isTrusted = quality >= QUALITY_EXACT_ID || bounds.width() > bounds.height() * 2
    if (isWideEnough && isTrusted && isBetter(bounds, quality, scan)) {
      scan.best = bounds
      scan.bestQuality = quality
    }
  }

  private fun trimYtMusicTrack(
    node: AccessibilityNodeInfo,
    parent: AccessibilityNodeInfo?,
    bounds: Rect,
    labelGapPx: Int,
    scan: Scan
  ) {
    if (isYtMusicTimeBar(parent)) {
      scan.hasYtMusicTimeBar = true
      parent?.let { container ->
        val containerBounds = Rect().also { container.getBoundsInScreen(it) }
        if (containerBounds.width() > MIN_BAR_WIDTH_PX) {
          bounds.set(
            trimYtMusicTimeLabels(
              container = containerBounds,
              elapsedLabel = childBounds(container, TIME_LABEL_CURRENT_SUFFIX),
              totalLabel = childBounds(container, TIME_LABEL_TOTAL_SUFFIX),
              labelGapPx = labelGapPx,
            ),
          )
        }
      }
    } else if (isPlaybackTimeBar(node)) {
      scan.hasYtMusicTimeBar = true
      bounds.set(trimUnlabeledYtMusicTrack(bounds))
    }
  }

  // Prefer the shortest match at a given quality — closest to the real track rect.
  private fun isBetter(
    bounds: Rect,
    quality: Int,
    scan: Scan
  ): Boolean {
    if (quality != scan.bestQuality) {
      return quality > scan.bestQuality
    }
    val best = scan.best
    return best == null || bounds.height() < best.height()
  }

  private fun childBounds(
    parent: AccessibilityNodeInfo,
    idSuffix: String
  ): Rect? {
    for (i in 0 until parent.childCount) {
      val child = parent.getChild(i) ?: continue
      if (child.viewIdResourceName?.endsWith(idSuffix) == true) {
        return Rect().also { child.getBoundsInScreen(it) }
      }
    }
    return null
  }

  /**
   * 4 = the real SeekBar control, 3 = exact time-bar id, 2 = TimeBar class, 1 = range node, 0 = no.
   *
   * The actual scrubber is exposed as an `android.widget.SeekBar` whose bounds are tight around the
   * track (its vertical center IS the seek line) — more precise than the exact-id container, which
   * spans the whole player. So we rank it highest and let the overlay center on it.
   */
  private fun seekbarQuality(
    node: AccessibilityNodeInfo,
    parent: AccessibilityNodeInfo?
  ): Int {
    val cls = node.className?.toString()
    // Recent YT Music versions report the SeekBar class but omit RangeInfo and flatten away its
    // parent for an AccessibilityService. Its named parent or the playback-only "current of total"
    // description is an equally strong signal.
    val isTimeBarNode = node.rangeInfo != null || isYtMusicTimeBar(parent) || isPlaybackTimeBar(node)
    if (cls == "android.widget.SeekBar" && isTimeBarNode) {
      return QUALITY_SEEKBAR
    }
    val id = node.viewIdResourceName
    if (id != null && SEEKBAR_ID_SUFFIXES.any { id.endsWith(it) }) {
      return QUALITY_EXACT_ID
    }
    if (cls != null && cls.contains("TimeBar")) {
      return QUALITY_CLASS
    }
    if (node.rangeInfo != null) {
      return QUALITY_RANGE
    }
    return QUALITY_NONE
  }

  private fun isYtMusicTimeBar(parent: AccessibilityNodeInfo?): Boolean =
    parent?.viewIdResourceName?.let { id ->
      CONTAINER_ID_SUFFIXES.any { id.endsWith(it) }
    } == true

  private fun isPlaybackTimeBar(node: AccessibilityNodeInfo): Boolean =
    node.contentDescription?.toString()?.contains(" of ") == true

  /**
   * Turns YT Music's controls-row bounds into its actual drawable seek-track bounds.
   *
   * The service can only read node rectangles; `time_bar_reference_view` is not exposed to
   * accessibility. The elapsed and total-time labels are exposed, however, and consistently
   * flank the track. Keep the result inside [container] when a compact layout omits a label.
   */
  internal fun trimYtMusicTimeLabels(
    container: Rect,
    elapsedLabel: Rect?,
    totalLabel: Rect?,
    labelGapPx: Int,
  ): Rect {
    val track = Rect(container)
    elapsedLabel?.let { track.left = (it.right + labelGapPx).coerceAtMost(track.right) }
    totalLabel?.let { track.right = (it.left - labelGapPx).coerceAtLeast(track.left) }
    return track
  }

  /**
   * Fallback for YT Music fullscreen's flattened accessibility subtree. Its SeekBar bounds cover
   * the timer labels and the right-hand chapters button; these fractions were measured on the
   * live Material player and scale with display width. The container/label path above is used
   * whenever that richer tree is available.
   */
  internal fun trimUnlabeledYtMusicTrack(seekbar: Rect): Rect {
    val width = seekbar.width()
    return Rect(seekbar).apply {
      left += (width * UNLABELED_LEFT_INSET_FRACTION).roundToInt()
      right -= (width * UNLABELED_RIGHT_INSET_FRACTION).roundToInt()
    }
  }

  private const val QUALITY_NONE = 0
  private const val QUALITY_RANGE = 1
  private const val QUALITY_CLASS = 2
  private const val QUALITY_EXACT_ID = 3
  private const val QUALITY_SEEKBAR = 4
  private const val MAX_NODES = 3000
  private const val MIN_BAR_WIDTH_PX = 240

  // The time labels sit one standard 8 dp spacing unit outside the actual line.
  private const val YT_MUSIC_LABEL_GAP_DP = 8f

  // Real ids from YouTube 21.26.364 (aapt2 dump). The InlineTimeBarWrapper scrubber carries a
  // different id per context: inline watch, fullscreen large-controls, floaty/mini, and generic.
  private val SEEKBAR_ID_SUFFIXES =
    listOf(
      "/inline_playback_time_bar",
      "/large_controls_time_bar",
      "/floaty_bar_time_bar_view",
      "/player_seekbar",
      "/time_bar",
      // Portrait/inline watch exposes no thin bar node — only these full-player containers whose
      // bottom edge is where the track sits (verified via uiautomator on YT 21.26.364).
      "/watch_while_time_bar_view_overlay",
      "/watch_while_time_bar_view",
    )

  // YT Music's scrubber has no id of its own; its parent RelativeLayout carries this and its
  // bounds contain both the track and time labels.
  private val CONTAINER_ID_SUFFIXES = listOf("/time_bar_container")
  private const val TIME_LABEL_CURRENT_SUFFIX = "/time_bar_current_time"
  private const val TIME_LABEL_TOTAL_SUFFIX = "/time_bar_total_time"

  // Present only while the player controls are on screen — our "seekbar is visible" signal.
  private val CONTROLS_MARKER_SUFFIXES =
    listOf(
      "/time_bar_current_time",
      "/player_control_play_pause_replay_button",
      "/mini_player_play_pause_replay_button",
    )
  private const val UNLABELED_LEFT_INSET_FRACTION = 0.0553f
  private const val UNLABELED_RIGHT_INSET_FRACTION = 0.0941f
}
