package dev.sponsorblock.companion.cast

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal const val LOUNGE_BASE = "https://www.youtube.com"

/** The pairing half of the Lounge protocol: screen id, lounge token, then the code to show. */
internal class LoungePairing(
  private val http: OkHttpClient,
  private val deviceId: String,
  private val screenName: String,
  private val screenApp: String
) {
  /** Reuses [existingScreenId] when given, otherwise mints a screen, and returns its pairing. */
  fun pair(existingScreenId: String?): LoungeClient.Pairing {
    val screenId = existingScreenId ?: generateScreenId()
    val loungeToken = getLoungeToken(screenId)
    return LoungeClient.Pairing(screenId, loungeToken, getPairingCode(screenId, loungeToken))
  }

  private fun generateScreenId(): String =
    http
      .newCall(Request.Builder().url("$LOUNGE_BASE/api/lounge/pairing/generate_screen_id").build())
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
        .url("$LOUNGE_BASE/api/lounge/pairing/get_lounge_token_batch")
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
        .url("$LOUNGE_BASE/api/lounge/pairing/get_pairing_code?ctx=pair")
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
}
