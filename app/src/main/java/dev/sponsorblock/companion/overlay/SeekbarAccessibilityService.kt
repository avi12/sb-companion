package dev.sponsorblock.companion.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.sponsorblock.companion.Config

/**
 * Paints SponsorBlock segments on the STOCK YouTube seekbar without modifying YouTube.
 *
 * We can't inject views into YouTube's hierarchy, so we:
 *  1. locate the scrubber node via the accessibility tree (getBoundsInScreen), and
 *  2. draw the colored bars in a TYPE_ACCESSIBILITY_OVERLAY window on top of it — a window type an
 *     AccessibilityService may add with no draw-over-apps permission dialog.
 *
 * Bounds are re-read (debounced) on the events that move/hide the bar: content changes, scrolls,
 * and window-state changes. When YouTube isn't the foreground app, or the scrubber isn't present
 * (controls auto-hidden / not a watch page), the overlay is hidden.
 */
class SeekbarAccessibilityService : AccessibilityService() {
  private val handler = Handler(Looper.getMainLooper())
  private val config by lazy { Config(this) }

  private var windowManager: WindowManager? = null
  private var overlay: SeekbarOverlayView? = null
  private var lastBounds: Rect? = null

  private val rescan = Runnable { scanAndDraw() }

  override fun onServiceConnected() {
    windowManager = getSystemService(WindowManager::class.java)
    addOverlay()
    OverlayState.onChange = { handler.post { overlay?.invalidate() } }
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    OverlayState.onChange = null
    handler.removeCallbacksAndMessages(null)
    removeOverlay()
    return super.onUnbind(intent)
  }

  override fun onInterrupt() = Unit

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    event ?: return
    val pkg = event.packageName?.toString()
    if (pkg != null && pkg !in config.targetPackages) {
      // Left YouTube (or an unrelated app moved to front): stop painting.
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        setBounds(null)
      }
      return
    }
    // Coalesce the storm of events YouTube emits into a single debounced rescan.
    handler.removeCallbacks(rescan)
    handler.postDelayed(rescan, DEBOUNCE_MS)
  }

  private fun scanAndDraw() {
    if (!config.seekbarOverlayEnabled) {
      setBounds(null)
      return
    }
    val root = runCatching { rootInActiveWindow }.getOrNull()
    if (root == null || root.packageName?.toString() !in config.targetPackages) {
      setBounds(null)
      return
    }
    setBounds(findSeekbar(root))
  }

  private fun setBounds(bounds: Rect?) {
    if (bounds == lastBounds) {
      return
    }
    lastBounds = bounds
    overlay?.update(bounds)
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
  private fun findSeekbar(root: AccessibilityNodeInfo): Rect? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var best: Rect? = null
    var bestQuality = 0
    // Only paint when the player controls are actually up. YouTube keeps a persistent thin progress
    // line (and its container node) even after the controls auto-hide, so a bar node alone isn't
    // enough — we require a controls-only marker (the elapsed-time label / play-pause button) to be
    // present, and hide the overlay the moment it's gone.
    var controlsVisible = false
    var guard = 0
    while (queue.isNotEmpty() && guard < MAX_NODES) {
      guard++
      val node = queue.removeFirst()
      val id = node.viewIdResourceName
      if (id != null && CONTROLS_MARKER_SUFFIXES.any { id.endsWith(it) }) {
        controlsVisible = true
      }
      val quality = seekbarQuality(node)
      if (quality > 0) {
        val r = Rect().also { node.getBoundsInScreen(it) }
        // An exact id (quality 3) is trusted on its own; weaker signals (TimeBar class / RangeInfo)
        // must be wide-and-short so we don't grab a volume or settings slider.
        val accept = r.width() > MIN_BAR_WIDTH_PX && (quality >= 3 || r.width() > r.height() * 2)
        if (accept) {
          // Prefer the shortest match at a given quality — closest to the real track rect.
          if (quality > bestQuality || (quality == bestQuality && (best == null || r.height() < best.height()))) {
            best = r
            bestQuality = quality
          }
        }
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { queue.add(it) }
      }
    }
    return if (controlsVisible) best else null
  }

  /**
   * 4 = the real SeekBar control, 3 = exact time-bar id, 2 = TimeBar class, 1 = range node, 0 = no.
   *
   * The actual scrubber is exposed as an `android.widget.SeekBar` whose bounds are tight around the
   * track (its vertical center IS the seek line) — more precise than the exact-id container, which
   * spans the whole player. So we rank it highest and let the overlay center on it.
   */
  private fun seekbarQuality(node: AccessibilityNodeInfo): Int {
    val cls = node.className?.toString()
    if (cls == "android.widget.SeekBar") {
      return 4
    }
    val id = node.viewIdResourceName
    if (id != null && SEEKBAR_ID_SUFFIXES.any { id.endsWith(it) }) {
      return 3
    }
    if (cls != null && cls.contains("TimeBar")) {
      return 2
    }
    return if (node.rangeInfo != null) 1 else 0
  }

  private fun addOverlay() {
    if (overlay != null) {
      return
    }
    val view = SeekbarOverlayView(this)
    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      ).apply { gravity = Gravity.TOP or Gravity.START }
    runCatching { windowManager?.addView(view, params) }
      .onSuccess { overlay = view }
  }

  private fun removeOverlay() {
    overlay?.let { v -> runCatching { windowManager?.removeView(v) } }
    overlay = null
    lastBounds = null
  }

  companion object {
    private const val DEBOUNCE_MS = 100L
    private const val MAX_NODES = 3000
    private const val MIN_BAR_WIDTH_PX = 240
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

    // Present only while the player controls are on screen — our "seekbar is visible" signal.
    private val CONTROLS_MARKER_SUFFIXES =
      listOf(
        "/time_bar_current_time",
        "/player_control_play_pause_replay_button",
      )
  }
}
