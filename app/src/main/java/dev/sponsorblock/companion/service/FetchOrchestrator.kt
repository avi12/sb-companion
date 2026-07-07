package dev.sponsorblock.companion.service

import android.util.Log
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.cast.CastCapture
import dev.sponsorblock.companion.media.SkipEngine
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
  private var lastFetchedId: String? = null
  private var fetchJob: Job? = null

  /** Forget the current session's metadata/fetch state (called when the active controller changes). */
  fun reset() {
    currentMeta = null
    lastFetchedId = null
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
    currentMeta = next
    launchFetch()
  }

  /** Resolves the videoId (captured cast id first) and fetches its segments for the local session. */
  fun launchFetch() {
    val (title, artist, durationMs) = currentMeta ?: return
    fetchJob?.cancel()
    fetchJob =
      scope.launch {
        val videoId =
          CastCapture.videoId
            ?: VideoIdResolver.resolve(title, artist.orEmpty())
            ?: return@launch
        if (videoId == lastFetchedId) {
          return@launch
        }
        lastFetchedId = videoId
        engineProvider()?.setSegments(emptyList()) // clear stale segments immediately
        OverlayState.clear()
        // Fetch all action types so the seekbar overlay can paint highlights/chapters too;
        // the skip engine still only acts on skip-type, auto-enabled segments.
        val all =
          runCatching {
            sb.fetch(videoId, config.enabledCategories)
          }.onFailure { Log.w(TAG, "segment fetch failed for $videoId", it) }.getOrDefault(emptyList())
        val skippable =
          all
            .filter { it.actionType == "skip" }
            .map { it.copy(autoSkip = config.isAutoSkip(it.category)) }
        Log.i(TAG, "$videoId: ${all.size} segments, ${skippable.count { it.autoSkip }} auto-skip")
        engineProvider()?.setSegments(skippable)
        OverlayState.publish(all, durationMs / 1000.0)
      }
  }

  private companion object {
    const val TAG = "SBco"
  }
}
