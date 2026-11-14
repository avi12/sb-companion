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
    if (!config.isSeekbarOverlayEnabled) {
      setBounds(null)
      return
    }
    val root = runCatching { rootInActiveWindow }.getOrNull()
    if (root == null || root.packageName?.toString() !in config.targetPackages) {
      setBounds(null)
      return
    }
    setBounds(SeekbarFinder.find(root, resources.displayMetrics.density))
  }

  private fun setBounds(bounds: Rect?) {
    if (bounds == lastBounds) {
      return
    }
    lastBounds = bounds
    overlay?.update(bounds)
  }

  private fun addOverlay() {
    if (overlay != null) {
      return
    }
    val view = SeekbarOverlayView(overlayContext ?: this)
    val params =
      WindowManager
        .LayoutParams(
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

  private companion object {
    // Bounds only change when controls/layout change, not for every playback-position update.
    // This keeps the accessibility tree walk cheap while a player is foregrounded.
    const val DEBOUNCE_MS = 250L
  }
}
