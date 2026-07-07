package dev.sponsorblock.companion.ui

import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialContainerTransform

/**
 * Owns every looping demo preview player and the fullscreen container-transform overlay. A single
 * instance is shared by the onboarding "how it works" page and the main screen; both bind previews
 * that morph into the same fullscreen overlay, keeping playback continuous.
 */
@OptIn(UnstableApi::class)
class DemoPlayerController(
  private val activity: AppCompatActivity,
  private val rootFrame: ViewGroup,
  private val expandRoot: View,
  private val expandPlayer: PlayerView
) {
  /** Every looping preview player, released together in [release]. */
  private val players = mutableListOf<ExoPlayer>()

  /** The inline PlayerView currently expanded (so we can morph back to it). */
  private var expandedFrom: PlayerView? = null

  /** res/raw/demo.mp4 as a Media3 rawresource URI, or null if it isn't bundled. */
  private val demoUri: Uri? by lazy {
    val resId = activity.resources.getIdentifier("demo", "raw", activity.packageName)
    if (resId == 0) {
      null
    } else {
      RawResourceDataSource.buildRawResourceUri(resId)
    }
  }

  /** Whether the fullscreen overlay is currently showing. */
  val isExpanded: Boolean
    get() = expandRoot.isVisible

  /**
   * Binds a looping, muted preview player (with native controls) to [playerViewId]. The controls'
   * fullscreen button expands the clip via a Material container transform. No-op if the demo clip
   * isn't bundled, leaving the static mock in place.
   */
  fun bind(
    page: View,
    playerViewId: Int,
    mockId: Int
  ) {
    val uri = demoUri ?: return
    val playerView = page.findViewById<PlayerView>(playerViewId)
    val mock = page.findViewById<View>(mockId)

    val player =
      ExoPlayer.Builder(activity).build().apply {
        setMediaItem(MediaItem.fromUri(uri))
        repeatMode = Player.REPEAT_MODE_ONE // loop by default
        volume = 0f
        playWhenReady = true
        prepare()
        addListener(
          object : Player.Listener {
            override fun onRenderedFirstFrame() {
              mock.visibility = View.GONE
            }
          }
        )
      }
    players.add(player)
    playerView.player = player
    playerView.setFullscreenButtonClickListener { expand(playerView) }
  }

  /**
   * Grows the tapped preview into the fullscreen overlay via MaterialContainerTransform. The SAME
   * player is handed to the fullscreen view with [PlayerView.switchTargetView], so playback is
   * continuous with no re-buffer or reload.
   */
  private fun expand(source: PlayerView) {
    val player = source.player ?: return
    expandedFrom = source
    expandPlayer.setFullscreenButtonClickListener { collapse() }

    beginTransition(source, expandRoot, EXPAND_MS)
    source.visibility = View.INVISIBLE
    expandRoot.visibility = View.VISIBLE
    PlayerView.switchTargetView(player, source, expandPlayer)
  }

  /** Shrinks the fullscreen overlay back into the preview it came from, handing the player back. */
  fun collapse() {
    val source = expandedFrom ?: return
    val player = expandPlayer.player ?: return
    beginTransition(expandRoot, source, SHRINK_MS)
    expandRoot.visibility = View.GONE
    source.visibility = View.VISIBLE
    PlayerView.switchTargetView(player, expandPlayer, source)
    expandedFrom = null
  }

  /** Runs a Material container transform from [start] to [end], unless animations are disabled. */
  private fun beginTransition(
    start: View,
    end: View,
    durationMs: Long
  ) {
    if (!activity.animationsEnabled()) {
      return
    }
    val transform =
      MaterialContainerTransform().apply {
        startView = start
        endView = end
        addTarget(end)
        scrimColor = Color.TRANSPARENT
        duration = durationMs
        interpolator = emphasized
      }
    TransitionManager.beginDelayedTransition(rootFrame, transform)
  }

  /** Releases every preview player; call from the host Activity's onDestroy. */
  fun release() {
    players.forEach { it.release() }
  }

  companion object {
    private const val EXPAND_MS = 300L
    private const val SHRINK_MS = 230L

    /** M3 "emphasized" easing. */
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
  }
}
