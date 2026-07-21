package dev.sponsorblock.companion.service

import android.util.Log
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.cast.CastCapture
import dev.sponsorblock.companion.media.SkipEngine
import dev.sponsorblock.companion.model.Segment
import dev.sponsorblock.companion.net.SponsorBlockClient
import dev.sponsorblock.companion.net.VideoIdResolver
import dev.sponsorblock.companion.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns videoId resolution, segment fetching and overlay/skip-engine publishing for the active
 * media session. The listener service stays a lifecycle shell: it feeds new metadata in and points
 * the orchestrator at whichever [SkipEngine] is currently attached.
 */
class FetchOrchestrator(
  private val scope: CoroutineScope,
  private val config: Config,
  private val sb: SponsorBlockClient,
  private val engineProvider: () -> SkipEngine?
) {
  @Volatile private var currentMeta: Triple<String, String?, Long>? = null

  // A cast can temporarily remove/recreate the local media session. Keep an exact id for its
  // matching metadata across that hand-off, then discard it as soon as a different track appears.
  private var capturedId: String? = null
  private var capturedForMeta: Triple<String, String?, Long>? = null
  // Keep the most recent response. A controller can be recreated as a cast connects/disconnects
  // while its metadata is unchanged; the new SkipEngine still needs the segments even though a
  // second network request would be redundant.
  private var cachedFetch: CachedFetch? = null
  private var fetchJob: Job? = null

  /** Forget the current session's metadata/fetch state (called when the active controller changes). */
  fun reset() {
    currentMeta = null
  }

  /** New local metadata arrived — remember it and (re)fetch. Blank titles are ignored. */
  fun onNewVideo(
    title: String?,
    artist: String?,
    durationMs: Long
  ) {
    if (title.isNullOrBlank()) {
      return
    }
    val next = Triple(title, artist, durationMs)
    // The session emits metadata twice on attach (initial read + first callback); skip the
    // duplicate so we don't kick off a second, redundant videoId resolve for the same track.
    if (next == currentMeta) {
      return
    }
    if (capturedForMeta != null && capturedForMeta != next) {
      capturedId = null
      capturedForMeta = null
    }
    currentMeta = next
    clearPublishedSegments()
    launchFetch()
  }

  /** Resolves the videoId (captured cast id first) and fetches its segments for the local session. */
  fun launchFetch() {
    val meta = currentMeta ?: return
    CastCapture.takeVideoId()?.let { id ->
      capturedId = id
      capturedForMeta = meta
    }
    val (title, artist, durationMs) = meta
    fetchJob?.cancel()
    fetchJob =
      scope.launch {
        val videoId =
          // A cast capture is an exact ID for this metadata. It survives the brief cast-session
          // hand-off, but onNewVideo clears it before a later YT Music track can reuse it.
          capturedId?.takeIf { capturedForMeta == meta }
            ?: VideoIdResolver.resolve(title, artist.orEmpty())
            ?: return@launch
        val cached = cachedFetch
        if (cached?.videoId == videoId) {
          publish(cached.segments, durationMs)
          return@launch
        }
        // Fetch all action types so the seekbar overlay can paint highlights/chapters too;
        // the skip engine still only acts on skip-type, auto-enabled segments.
        val all =
          runCatching {
            sb.fetch(videoId, config.enabledCategories)
          }.onFailure { Log.w(TAG, "segment fetch failed for $videoId", it) }.getOrNull()
            ?: return@launch
        cachedFetch = CachedFetch(videoId, all)
        publish(all, durationMs)
      }
  }

  private fun clearPublishedSegments() {
    engineProvider()?.setSegments(emptyList())
    OverlayState.clear()
  }

  private fun publish(
    all: List<Segment>,
    durationMs: Long,
  ) {
    val skippable =
      all
        .filter { it.actionType == "skip" }
        .map { it.copy(autoSkip = config.isAutoSkip(it.category)) }
    Log.i(
      TAG,
      "${cachedFetch?.videoId}: ${all.size} segments, ${skippable.count { it.autoSkip }} auto-skip",
    )
    engineProvider()?.setSegments(skippable)
    OverlayState.publish(all, durationMs / 1000.0)
  }

  private data class CachedFetch(
    val videoId: String,
    val segments: List<Segment>,
  )

  private companion object {
    const val TAG = "SBco"
  }
}
