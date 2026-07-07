package dev.sponsorblock.companion.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.sponsorblock.companion.model.Segment

/**
 * The core capability. Given a MediaController for the playing app's session, it watches the
 * PlaybackState, extrapolates the live position, and schedules a single timer to seek past the
 * next auto-skip segment — re-verifying right before firing so an ad shift or a manual scrub
 * can't cause a bad skip.
 *
 * All three primitives this relies on were verified in YouTube 21.26.364:
 *  - PlaybackState carries position + updateTime + speed  (fh.d(state, pos, speed))
 *  - the session honors onSeekTo  (kwr.g(long) -> player seek)
 *  - ACTION_SEEK_TO (256) is advertised whenever the video is seekable
 *
 * SkipEngine is deliberately decoupled from MediaSessionManager so it can be unit/instrumented
 * tested against a self-hosted MediaSession (see SkipEngineInstrumentedTest).
 */
class SkipEngine(
  private val controller: MediaController,
  private val handler: Handler = Handler(Looper.getMainLooper()),
  private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
  private val onSkip: (Segment) -> Unit = {},
  private val onNewVideo: (title: String?, artist: String?, durationMs: Long) -> Unit = { _, _, _ -> },
) {
  @Volatile private var segments: List<Segment> = emptyList()
  private var pending: Runnable? = null

  private val callback =
    object : MediaController.Callback() {
      override fun onPlaybackStateChanged(state: PlaybackState?) = reschedule()

      override fun onMetadataChanged(metadata: MediaMetadata?) = emitMetadata(metadata)

      override fun onSessionDestroyed() = detach()
    }

  fun attach() {
    controller.registerCallback(callback, handler)
    emitMetadata(controller.metadata)
    reschedule()
  }

  fun detach() {
    cancel()
    runCatching { controller.unregisterCallback(callback) }
  }

  /** Segments already filtered/flagged for auto-skip by the caller (config applied). */
  fun setSegments(list: List<Segment>) {
    segments = list.filter { it.autoSkip }.sortedBy { it.start }
    reschedule()
  }

  private fun emitMetadata(m: MediaMetadata?) {
    onNewVideo(
      m?.getString(MediaMetadata.METADATA_KEY_TITLE),
      m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
      m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
    )
  }

  /** Extrapolated live position in ms, or null if not currently playing. */
  private fun livePositionMs(state: PlaybackState?): Long? {
    val s = state ?: controller.playbackState ?: return null
    if (s.state != PlaybackState.STATE_PLAYING) {
      return null
    }
    val speed =
      if (s.playbackSpeed > 0.1f) {
        s.playbackSpeed
      } else {
        1f
      }
    val delta = (nowMs() - s.lastPositionUpdateTime).coerceAtLeast(0)
    return (s.position + delta * speed).toLong()
  }

  private fun reschedule() {
    cancel()
    val state = controller.playbackState ?: return
    val posMs = livePositionMs(state) ?: return
    val posSec = posMs / 1000.0
    val seg = segments.firstOrNull { it.end > posSec + 0.2 } ?: return
    if (posSec >= seg.start - 0.05) { // already inside -> skip now
      doSkip(seg)
      return
    }
    val speed =
      if (state.playbackSpeed > 0.1f) {
        state.playbackSpeed
      } else {
        1f
      }
    val delayMs = (((seg.start - posSec) * 1000.0) / speed).toLong().coerceAtLeast(0)
    Runnable { verifyThenSkip(seg) }.also {
      pending = it
      handler.postDelayed(it, delayMs)
    }
  }

  private fun verifyThenSkip(seg: Segment) {
    val posMs = livePositionMs(controller.playbackState) ?: return reschedule()
    val posSec = posMs / 1000.0
    // guard: only skip if we really landed at/inside the segment (not an ad shift or user seek)
    if (posSec in (seg.start - GUARD_SEC)..seg.end) {
      doSkip(seg)
    } else {
      reschedule()
    }
  }

  private fun doSkip(seg: Segment) {
    val targetMs = (seg.end * 1000).toLong() + EPSILON_MS // land just past the end
    android.util.Log.i("SkipEngine", "SKIP ${seg.category} ${seg.start.toInt()}s->${seg.end.toInt()}s")
    controller.transportControls.seekTo(targetMs)
    onSkip(seg)
    // the seek produces a fresh PlaybackState (-> callback -> reschedule); also nudge defensively
    handler.postDelayed({ reschedule() }, 600)
  }

  private fun cancel() {
    pending?.let { handler.removeCallbacks(it) }
    pending = null
  }

  companion object {
    private const val EPSILON_MS = 300L // absorb seek latency so we don't fall back into the segment
    private const val GUARD_SEC = 1.5
  }
}
