package dev.sponsorblock.companion.net

import dev.sponsorblock.companion.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

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
    suspend fun fetch(
        videoId: String,
        categories: List<String>,
        actionTypes: List<String> = listOf("skip", "mute", "poi", "full"),
    ): List<Segment> = withContext(Dispatchers.IO) {
        val prefix = Hashing.prefix(videoId, 5)
        val url = "$server/api/skipSegments/$prefix".toHttpUrl().newBuilder()
            .addQueryParameter("categories", JSONArray(categories).toString())
            .addQueryParameter("actionTypes", JSONArray(actionTypes).toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .header("X-CLIENT-NAME", CLIENT_NAME)
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> emptyList()
                !resp.isSuccessful -> throw IOException("SponsorBlock HTTP ${resp.code}")
                else -> parse(resp.body?.string().orEmpty(), videoId)
            }
        }
    }

    private fun parse(body: String, videoId: String): List<Segment> {
        if (body.isBlank()) return emptyList()
        val videos = JSONArray(body)
        val out = ArrayList<Segment>()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            if (v.optString("videoID") != videoId) continue // prefix returns many videos
            val segs = v.getJSONArray("segments")
            for (j in 0 until segs.length()) {
                val s = segs.getJSONObject(j)
                val pair = s.getJSONArray("segment")
                out.add(
                    Segment(
                        start = pair.getDouble(0),
                        end = pair.getDouble(1),
                        category = s.getString("category"),
                        actionType = s.optString("actionType", "skip"),
                        uuid = s.optString("UUID"),
                    ),
                )
            }
        }
        return out.sortedBy { it.start }
    }

    companion object {
        const val DEFAULT_SERVER = "https://sponsor.ajay.app"
        const val CLIENT_NAME = "sb-android-companion/0.1"
    }
}
