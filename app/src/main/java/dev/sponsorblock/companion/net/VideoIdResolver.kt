package dev.sponsorblock.companion.net

import dev.sponsorblock.companion.model.MILLIS_PER_SECOND
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Resolves title + channel -> videoID, because the MediaSession never exposes the ID
 * (verified: YouTube's session sets only TITLE/ARTIST/DURATION/ART, no MEDIA_ID/MEDIA_URI).
 *
 * This is a BEST-GUESS: it takes the top /watch?v= match from a YouTube search over
 * "title channel". It can pick a re-upload or the wrong ATV/OMV twin. The caller MUST validate
 * with the duration cross-check (durationMatches) and fail closed when uncertain.
 *
 * For exact IDs (user uploads, ambiguous titles) the cast-grab path is the fallback — see docs/RESEARCH.md.
 */
object VideoIdResolver {
  private val client = OkHttpClient()
  private val watchRe = Regex("""/watch\?v=([A-Za-z0-9_-]{11})""")
  private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

  /** Searches YouTube for the title and channel and returns the first video id, or null. */
  suspend fun resolve(
    title: String,
    channel: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
  ): String? =
    withContext(dispatcher) {
      val query = URLEncoder.encode("$title $channel".trim(), "UTF-8")
      val req =
        Request
          .Builder()
          .url("https://www.youtube.com/results?search_query=$query")
          .header("User-Agent", UA)
          .build()
      runCatching {
        client.newCall(req).execute().use { it.body?.string() }
      }.getOrNull()?.let { watchRe.find(it)?.groupValues?.get(1) }
    }

  /** Sanity check against the session's reported duration (±2 s). */
  fun durationMatches(
    reportedMs: Long,
    candidateSeconds: Double,
    toleranceSec: Double = 2.0
  ): Boolean {
    if (reportedMs <= 0) {
      return true
    } // unknown -> don't block, but caller may still fail closed
    return kotlin.math.abs(reportedMs / MILLIS_PER_SECOND - candidateSeconds) <= toleranceSec
  }
}
