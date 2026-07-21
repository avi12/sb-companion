package dev.sponsorblock.companion.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.sponsorblock.companion.Config
import kotlin.math.roundToInt

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

  // A service/application Context is fixed to this app's default (portrait) configuration. On a
  // landscape YouTube Music player that gives the overlay a portrait canvas which WindowManager
  // rotates afterwards, so screen-space seekbar bounds are painted in the wrong place. A window
  // context tracks the target display's current configuration instead.
  private var overlayContext: Context? = null
  private var windowManager: WindowManager? = null
  private var overlay: SeekbarOverlayView? = null
  private var lastBounds: Rect? = null

  private val rescan = Runnable { scanAndDraw() }

  override fun onServiceConnected() {
    overlayContext =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // AccessibilityService is not itself associated with a display on Android 17. Bind it to
        // the default display before asking for a configuration-aware window context.
        val display =
          getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
          createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else {
          this
        }
      } else {
        this
      }
    windowManager = overlayContext?.getSystemService(WindowManager::class.java)
    addOverlay()
    // Segment data can arrive after the player first emits its accessibility events. Re-scan once
    // when it does, otherwise a seekbar that was already visible has no bounds to paint against
    // until the user touches the player controls again.
    OverlayState.onChange = { handler.post { scheduleRescan() } }
    scheduleRescan()
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
    scheduleRescan()
  }

  private fun scheduleRescan() {
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
    // AccessibilityNodeInfo.parent is unexpectedly null for YT Music's SeekBar on recent builds.
    // Keep the relationship while walking instead of asking the node to reconstruct it later.
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>>()
    queue.add(root to null)
    var best: Rect? = null
    var bestQuality = 0
    // Only paint when the player controls are actually up. YouTube keeps a persistent thin progress
    // line (and its container node) even after the controls auto-hide, so a bar node alone isn't
    // enough — we require a controls-only marker (the elapsed-time label / play-pause button) to be
    // present, and hide the overlay the moment it's gone.
    var controlsVisible = false
    // Some current YT Music fullscreen variants expose only the progress SeekBar and omit both
    // the elapsed-time/play-pause nodes and the named container from accessibility, even while
    // their controls are visibly on screen. Its "current of total" description is specific enough
    // to trust in that case.
    var hasYtMusicTimeBar = false
    var guard = 0
    while (queue.isNotEmpty() && guard < MAX_NODES) {
      guard++
      val (node, parent) = queue.removeFirst()
      val id = node.viewIdResourceName
      if (id != null && CONTROLS_MARKER_SUFFIXES.any { id.endsWith(it) }) {
        controlsVisible = true
      }
      val quality = seekbarQuality(node, parent)
      if (quality > 0) {
        val r = Rect().also { node.getBoundsInScreen(it) }
        // YT Music exposes the scrubber as a bare `android.widget.SeekBar` with NO id. Its
        // accessibility bounds cover the entire controls row, including the elapsed and total-time
        // labels. The wrapping `time_bar_container` is a better starting point, but it includes
        // those labels too. Trim between the label edges to get the drawable track itself (see
        // docs/ytmusic-seekbar-reversing.md).
        if (quality == 4 && id.isNullOrEmpty()) {
          if (isYtMusicTimeBar(parent)) {
            hasYtMusicTimeBar = true
            parent?.let { p ->
              val pr = Rect().also { p.getBoundsInScreen(it) }
              if (pr.width() > MIN_BAR_WIDTH_PX) {
                r.set(
                  trimYtMusicTimeLabels(
                    container = pr,
                    elapsedLabel = childBounds(p, TIME_LABEL_CURRENT_SUFFIX),
                    totalLabel = childBounds(p, TIME_LABEL_TOTAL_SUFFIX),
                    labelGapPx = (YT_MUSIC_LABEL_GAP_DP * resources.displayMetrics.density).toInt(),
                  ),
                )
              }
            }
          } else if (isPlaybackTimeBar(node)) {
            hasYtMusicTimeBar = true
            r.set(trimUnlabeledYtMusicTrack(r))
          }
        }
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
        node.getChild(i)?.let { queue.add(it to node) }
      }
    }
    return if (controlsVisible || hasYtMusicTimeBar) best else null
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
    if (
      cls == "android.widget.SeekBar" &&
        (node.rangeInfo != null || isYtMusicTimeBar(parent) || isPlaybackTimeBar(node))
    ) {
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

  private fun isYtMusicTimeBar(parent: AccessibilityNodeInfo?): Boolean =
    parent?.viewIdResourceName?.let { id ->
      CONTAINER_ID_SUFFIXES.any { id.endsWith(it) }
    } == true

  private fun isPlaybackTimeBar(node: AccessibilityNodeInfo): Boolean =
    node.contentDescription?.toString()?.contains(" of ") == true

  private fun addOverlay() {
    if (overlay != null) {
      return
    }
    val view = SeekbarOverlayView(overlayContext ?: this)
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
    overlayContext = null
  }

  companion object {
    // Bounds only change when controls/layout change, not for every playback-position update.
    // This keeps the accessibility tree walk cheap while a player is foregrounded.
    private const val DEBOUNCE_MS = 250L
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

    private const val UNLABELED_LEFT_INSET_FRACTION = 0.0553f
    private const val UNLABELED_RIGHT_INSET_FRACTION = 0.0941f
  }
}
