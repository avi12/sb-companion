package dev.sponsorblock.companion.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.sponsorblock.companion.model.MILLIS_PER_SECOND
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

  /** Starts watching the playback position. */
  fun attach() {
    controller.registerCallback(callback, handler)
    emitMetadata(controller.metadata)
    reschedule()
  }

  /** Stops watching the playback position. */
  fun detach() {
    cancel()
    runCatching { controller.unregisterCallback(callback) }
  }

  /** Segments already filtered/flagged for auto-skip by the caller (config applied). */
  fun setSegments(list: List<Segment>) {
    segments = list.filter { it.isAutoSkip }.sortedBy { it.start }
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
    val playback = state ?: controller.playbackState ?: return null
    if (playback.state != PlaybackState.STATE_PLAYING) {
      return null
    }
    val delta = (nowMs() - playback.lastPositionUpdateTime).coerceAtLeast(0)
    return (playback.position + delta * speedOf(playback)).toLong()
  }

  private fun speedOf(state: PlaybackState): Float =
    if (state.playbackSpeed > MIN_PLAYBACK_SPEED) {
      state.playbackSpeed
    } else {
      1f
    }

  private fun reschedule() {
    cancel()
    val state = controller.playbackState ?: return
    val posMs = livePositionMs(state) ?: return
    val posSec = posMs / MILLIS_PER_SECOND
    val seg = segments.firstOrNull { it.end > posSec + LOOKAHEAD_SEC } ?: return
    if (posSec >= seg.start - ALREADY_INSIDE_SEC) { // already inside -> skip now
      doSkip(seg)
      return
    }
    val delayMs = ((seg.start - posSec) * MILLIS_PER_SECOND / speedOf(state)).toLong().coerceAtLeast(0)
    Runnable { verifyThenSkip(seg) }.also {
      pending = it
      handler.postDelayed(it, delayMs)
    }
  }

  private fun verifyThenSkip(seg: Segment) {
    val posMs = livePositionMs(controller.playbackState) ?: return reschedule()
    val posSec = posMs / MILLIS_PER_SECOND
    // guard: only skip if we really landed at/inside the segment (not an ad shift or user seek)
    if (posSec in seg.start - GUARD_SEC..seg.end) {
      doSkip(seg)
    } else {
      reschedule()
    }
  }

  private fun doSkip(seg: Segment) {
    val targetMs = (seg.end * MILLIS_PER_SECOND).toLong() + EPSILON_MS // land just past the end
    android.util.Log.i("SkipEngine", "SKIP ${seg.category} ${seg.start.toInt()}s->${seg.end.toInt()}s")
    controller.transportControls.seekTo(targetMs)
    onSkip(seg)
    // the seek produces a fresh PlaybackState (-> callback -> reschedule); also nudge defensively
    handler.postDelayed({ reschedule() }, RESCHEDULE_DELAY_MS)
  }

  private fun cancel() {
    pending?.let { handler.removeCallbacks(it) }
    pending = null
  }

  /** Timing constants for seeking. */
  companion object {
    private const val EPSILON_MS = 300L // absorb seek latency so we don't fall back into the segment
    private const val GUARD_SEC = 1.5
    private const val MIN_PLAYBACK_SPEED = 0.1f
    private const val LOOKAHEAD_SEC = 0.2
    private const val ALREADY_INSIDE_SEC = 0.05
    private const val RESCHEDULE_DELAY_MS = 600L
  }
}
