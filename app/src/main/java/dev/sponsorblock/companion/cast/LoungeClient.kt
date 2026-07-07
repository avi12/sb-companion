package dev.sponsorblock.companion.cast

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

  @Volatile private var running = false

  // ---------- pairing ----------

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
   */
  fun bindAndCapture(onVideoId: (String) -> Unit) {
    running = true
    initSession()
    var last: String? = null
    while (running) {
      streamRpc { videoId ->
        if (videoId != last) {
          last = videoId
          onVideoId(videoId)
        }
      }
    }
  }

  fun stop() {
    running = false
  }

  private fun initSession() {
    val rid = (41000..49999).random()
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

  private fun streamRpc(onVideoId: (String) -> Unit) {
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
    runCatching {
      http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        val source = resp.body?.source() ?: return
        while (running && !source.exhausted()) {
          val line = source.readUtf8Line() ?: break
          updateAid(line)
          Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
            .findAll(line)
            .forEach { onVideoId(it.groupValues[1]) }
        }
      }
    }
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
    val b =
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
            .substring(0, 12)
        ).addQueryParameter("t", "1")
    extra(b)
    return b.build().toString()
  }

  companion object {
    private const val BASE = "https://www.youtube.com"
    private const val DEVICE_INFO =
      """{"brand":"Generic","model":"SmartTV","year":0,"os":"Windows","osVersion":"10.0",""" +
        """"chipset":"","clientName":"TVHTML5","dialAdditionalDataSupportLevel":"unsupported",""" +
        """"mdxDialServerType":"MDX_DIAL_SERVER_TYPE_UNKNOWN"}"""
  }
}
