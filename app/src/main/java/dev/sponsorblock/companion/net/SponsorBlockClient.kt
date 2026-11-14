package dev.sponsorblock.companion.net

import dev.sponsorblock.companion.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Read-only SponsorBlock API client. Mirrors the extension's request exactly so any self-hosted
 * server works unchanged:
 *   GET {server}/api/skipSegments/{sha256(videoID)[0..5]}?categories=[...]&actionTypes=[...]
 * 404 => the video has no segments (a success, not an error).
 */
class SponsorBlockClient(
  private val server: String = DEFAULT_SERVER,
  private val client: OkHttpClient = OkHttpClient(),
) {
  /** Fetches the video's segments by hash prefix and keeps those of the exact video id. */
  suspend fun fetch(
    videoId: String,
    categories: List<String>,
    actionTypes: List<String> = listOf("skip", "mute", "poi", "full"),
  ): List<Segment> =
    withContext(Dispatchers.IO) {
      val prefix = Hashing.prefix(videoId)
      val url =
        "$server/api/skipSegments/$prefix"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("categories", JSONArray(categories).toString())
          .addQueryParameter("actionTypes", JSONArray(actionTypes).toString())
          .build()
      val req =
        Request
          .Builder()
          .url(url)
          .header("X-CLIENT-NAME", CLIENT_NAME)
          .build()
      client.newCall(req).execute().use { resp ->
        when {
          resp.code == HttpURLConnection.HTTP_NOT_FOUND -> emptyList()
          !resp.isSuccessful -> throw IOException("SponsorBlock HTTP ${resp.code}")
          else -> parse(resp.body?.string().orEmpty(), videoId)
        }
      }
    }

  private fun parse(
    body: String,
    videoId: String
  ): List<Segment> {
    if (body.isBlank()) {
      return emptyList()
    }
    val videos = JSONArray(body)
    val out = ArrayList<Segment>()
    for (i in 0 until videos.length()) {
      val video = videos.getJSONObject(i)
      if (video.optString("videoID") != videoId) {
        continue
      } // prefix returns many videos
      val segs = video.getJSONArray("segments")
      for (j in 0 until segs.length()) {
        val entry = segs.getJSONObject(j)
        val pair = entry.getJSONArray("segment")
        out.add(
          Segment(
            start = pair.getDouble(0),
            end = pair.getDouble(1),
            category = entry.getString("category"),
            actionType = entry.optString("actionType", "skip"),
            uuid = entry.optString("UUID"),
          ),
        )
      }
    }
    return out.sortedBy { it.start }
  }

  /** Server defaults. */
  companion object {
    const val DEFAULT_SERVER = "https://sponsor.ajay.app"
    const val CLIENT_NAME = "sb-android-companion/0.0.0"
  }
}
