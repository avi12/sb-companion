package dev.sponsorblock.companion.cast

import android.os.SystemClock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Kotlin port of the pairing + bind pipeline in the `yt-cast-receiver` npm package (verified
 * against its Constants.js / Session.js / BindParams.js / Message.js). Enough of a YouTube "TV
 * screen" for the stock app to cast to, so we can read the videoId it hands us:
 *
 *   generate_screen_id -> get_lounge_token_batch -> get_pairing_code   (pairing, shown to user)
 *   bind(initSession)  -> obtain SID + gsessionid
 *   bind(rpc long-poll) -> stream cast events; setPlaylist carries "videoId"
 */
class LoungeClient(
  private val deviceId: String,
  private val screenName: String,
  /** Reuse a previously obtained screen id to stay the same remembered device; null = mint one. */
  private val existingScreenId: String? = null,
  private val screenApp: String = "ytcr",
  private val http: OkHttpClient =
    OkHttpClient
      .Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS) // bind is a long-poll; never time it out
      .build(),
) {
  /** A pairing code the user types into YouTube's Cast picker. */
  data class Pairing(
    val screenId: String,
    val loungeToken: String,
    val pairingCode: String
  )

  private lateinit var loungeToken: String
  private lateinit var screenId: String

  // Bind-channel session state.
  private var sid: String? = null
  private var gsessionid: String? = null

  @Volatile private var lastAid = 0

  @Volatile private var isRunning = false

  // ---------- pairing ----------

  /** Registers a screen and returns the code to show the user. */
  fun requestPairing(): Pairing {
    screenId = existingScreenId ?: generateScreenId()
    loungeToken = getLoungeToken(screenId)
    val code = getPairingCode(screenId, loungeToken)
    return Pairing(screenId, loungeToken, code)
  }

  private fun generateScreenId(): String =
    http
      .newCall(Request.Builder().url("$BASE/api/lounge/pairing/generate_screen_id").build())
      .execute()
      .use {
        it.body
          ?.string()
          ?.trim()
          .orEmpty()
      }.ifEmpty { error("empty screen id") }

  private fun getLoungeToken(screenId: String): String {
    val body = FormBody.Builder().add("screen_ids", screenId).build()
    val req =
      Request
        .Builder()
        .url("$BASE/api/lounge/pairing/get_lounge_token_batch")
        .post(body)
        .build()
    http.newCall(req).execute().use { resp ->
      return JSONObject(resp.body?.string().orEmpty())
        .getJSONArray("screens")
        .getJSONObject(0)
        .getString("loungeToken")
    }
  }

  private fun getPairingCode(
    screenId: String,
    loungeToken: String
  ): String {
    val body =
      FormBody
        .Builder()
        .add("access_type", "permanent")
        .add("app", screenApp)
        .add("lounge_token", loungeToken)
        .add("screen_id", screenId)
        .add("screen_name", screenName)
        .add("device_id", deviceId)
        .build()
    val req =
      Request
        .Builder()
        .url("$BASE/api/lounge/pairing/get_pairing_code?ctx=pair")
        .post(body)
        .build()
    http.newCall(req).execute().use { resp ->
      return resp.body
        ?.string()
        ?.trim()
        .orEmpty()
        .ifEmpty { error("empty pairing code") }
    }
  }

  // ---------- bind channel ----------

  /**
   * Establishes the bind session, then long-polls for cast events, invoking [onVideoId] with each
   * distinct videoId the sender casts. Blocks until [stop]; reconnects when YouTube ends a poll.
   *
   * A healthy bind is a genuine long-poll: the request stays open for tens of seconds and streams
   * lines. But the lounge session is NOT permanent — once YouTube expires the SID (or the network
   * drops) every rpc request returns instantly with no data. Reconnecting with zero delay in that
   * state spins a hot loop that pegs the CPU and keeps the radio awake forever — the battery-hog
   * bug. So we back off exponentially on unproductive polls, re-pair the session once the backoff
   * grows past a threshold (the SID is almost certainly dead by then), and reset the moment a poll
   * proves healthy again.
   */
  fun bindAndCapture(onVideoId: (String) -> Unit) {
    isRunning = true
    var last: String? = null
    var backoffMs = MIN_BACKOFF_MS
    var shouldInit = true
    while (isRunning) {
      if (shouldInit) {
        if (!runCatching { initSession() }.isSuccess) {
          backoffMs = sleepBackoff(backoffMs)
          continue
        }
        shouldInit = false
      }
      val startedAt = SystemClock.elapsedRealtime()
      val isHealthy =
        streamRpc { videoId ->
          if (videoId != last) {
            last = videoId
            onVideoId(videoId)
          }
        }
      val lastedMs = SystemClock.elapsedRealtime() - startedAt
      if (isHealthy || lastedMs >= HEALTHY_POLL_MS) {
        // A real long-poll (stayed open / streamed data): reconnect promptly, reset backoff.
        backoffMs = MIN_BACKOFF_MS
      } else {
        // Instant, empty return -> session/network is unhealthy. Back off before retrying, and once
        // the backoff is large enough assume the SID died and re-pair from initSession().
        if (backoffMs >= REINIT_BACKOFF_MS) {
          shouldInit = true
        }
        backoffMs = sleepBackoff(backoffMs)
      }
    }
  }

  /** Sleep for [current] backoff (interruptibly), returning the next (doubled, capped) value. */
  private fun sleepBackoff(current: Long): Long {
    var slept = 0L
    while (isRunning && slept < current) {
      val chunk = minOf(SLEEP_CHUNK_MS, current - slept)
      if (!runCatching { Thread.sleep(chunk) }.isSuccess) {
        break
      }
      slept += chunk
    }
    return (current * 2).coerceAtMost(MAX_BACKOFF_MS)
  }

  /** Stops polling and cancels any in-flight long-poll. */
  fun stop() {
    isRunning = false
    // Interrupt an in-flight no-timeout bind immediately. Merely flipping [isRunning] would leave
    // the networking thread and radio held until YouTube eventually closes the long-poll.
    http.dispatcher.cancelAll()
  }

  private fun initSession() {
    sid = null
    gsessionid = null
    lastAid = 0
    val rid = (MIN_RID..MAX_RID).random()
    val url =
      bindUrl { b ->
        b
          .addQueryParameter("RID", rid.toString())
          .addQueryParameter("CVER", "1")
          .addQueryParameter("deviceInfo", DEVICE_INFO)
      }
    val req =
      Request
        .Builder()
        .url(url)
        .post(FormBody.Builder().add("count", "0").build())
        .build()
    val text = http.newCall(req).execute().use { it.body?.string().orEmpty() }
    sid = Regex("""\["c","([^"]+)"""").find(text)?.groupValues?.get(1)
    gsessionid = Regex("""\["S","([^"]+)"""").find(text)?.groupValues?.get(1)
    updateAid(text)
    check(sid != null && gsessionid != null) { "bind initSession missing SID/gsessionid" }
  }

  /** Runs one rpc long-poll. Returns true if it connected (2xx) and streamed at least one line. */
  private fun streamRpc(onVideoId: (String) -> Unit): Boolean {
    val url =
      bindUrl { b ->
        b
          .addQueryParameter("RID", "rpc")
          .addQueryParameter("SID", sid)
          .addQueryParameter("CI", "0")
          .addQueryParameter("AID", lastAid.toString())
          .addQueryParameter("gsessionid", gsessionid)
          .addQueryParameter("TYPE", "xmlhttp")
      }
    return runCatching {
      http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) {
          return@use false
        }
        val source = resp.body?.source() ?: return@use false
        var hasReadAny = false
        while (isRunning && !source.exhausted()) {
          val line = source.readUtf8Line() ?: break
          hasReadAny = true
          updateAid(line)
          Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
            .findAll(line)
            .forEach { onVideoId(it.groupValues[1]) }
        }
        hasReadAny
      }
    }.getOrDefault(false)
  }

  private fun updateAid(text: String) {
    Regex("""\[(\d+),\[""").findAll(text).forEach {
      val aid = it.groupValues[1].toIntOrNull() ?: return@forEach
      if (aid > lastAid) {
        lastAid = aid
      }
    }
  }

  /** Builds a /bind URL with the params common to every request, plus [extra]. */
  private fun bindUrl(extra: (okhttp3.HttpUrl.Builder) -> Unit): String {
    val urlBuilder =
      "$BASE/api/lounge/bc/bind"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("device", "LOUNGE_SCREEN")
        .addQueryParameter("id", deviceId)
        .addQueryParameter("obfuscatedGaiaId", "")
        .addQueryParameter("name", screenName)
        .addQueryParameter("app", screenApp)
        .addQueryParameter("theme", "cl")
        .addQueryParameter("capabilities", "dsp,mic,dpa,ntb")
        .addQueryParameter("cst", "m")
        .addQueryParameter("mdxVersion", "2")
        .addQueryParameter("loungeIdToken", loungeToken)
        .addQueryParameter("VER", "8")
        .addQueryParameter("v", "2")
        .addQueryParameter(
          "zx",
          UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, ZX_LENGTH)
        ).addQueryParameter("t", "1")
    extra(urlBuilder)
    return urlBuilder.build().toString()
  }

  /** Lounge protocol constants. */
  companion object {
    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val SLEEP_CHUNK_MS = 200L
    private const val MIN_RID = 41_000
    private const val MAX_RID = 49_999
    private const val ZX_LENGTH = 12

    // Re-pair the bind session once the backoff has doubled a few times (SID is almost certainly
    // dead by ~8s of accumulated backoff).
    private const val REINIT_BACKOFF_MS = 8_000L

    // A poll that stayed open at least this long counts as a healthy long-poll even if it streamed
    // no lines (YouTube holds idle binds open), so we don't back off on normal reconnects.
    private const val HEALTHY_POLL_MS = 5_000L
    private const val BASE = "https://www.youtube.com"
    private const val DEVICE_INFO =
      """{"brand":"Generic","model":"SmartTV","year":0,"os":"Windows","osVersion":"10.0",""" +
        """"chipset":"","clientName":"TVHTML5","dialAdditionalDataSupportLevel":"unsupported",""" +
        """"mdxDialServerType":"MDX_DIAL_SERVER_TYPE_UNKNOWN"}"""
  }
}
