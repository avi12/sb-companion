package dev.sponsorblock.companion.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.sponsorblock.companion.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The on-phone "fake TV". It runs only after the user explicitly enables SponsorBlock TV, then is
 * restored by the notification listener while that setting remains enabled. It stands up a Lounge
 * screen named "SponsorBlock TV" so the stock YouTube app can cast to it, and surfaces the pairing
 * so the user can link it.
 *
 * Status: the pairing pipeline (screen id -> lounge token -> pairing code) is implemented and runs
 * here. The bind long-poll that captures the cast videoId — and then feeds the existing fetch ->
 * SkipEngine path — is the next slice (marked TODO below).
 */
class CastReceiverService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val config by lazy { Config(this) }
  private var receiverJob: Job? = null
  private var client: LoungeClient? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int
  ): Int {
    startForeground(NOTIF_ID, notification("Starting SponsorBlock TV…"))
    // A service can receive several start requests (listener reconnect, activity recreation, or a
    // repeated tap). Starting a Lounge client for each one means several infinite long-polls and
    // is a substantial background network/battery drain.
    if (receiverJob?.isActive != true) {
      receiverJob = scope.launch { startReceiver() }
    }
    return START_NOT_STICKY
  }

  private fun startReceiver() {
    val newClient = LoungeClient(config.deviceId, Config.CAST_DEVICE_NAME, config.screenId)
    client = newClient
    val pairing =
      runCatching { newClient.requestPairing() }
        .onFailure { Log.w(TAG, "pairing failed", it) }
        .getOrNull()

    if (pairing == null) {
      update("Couldn't reach YouTube. Retrying when you reconnect.")
      return
    }

    config.screenId = pairing.screenId // stay the same remembered device next time
    CastCapture.pairingCode = pairing.pairingCode
    Log.i(TAG, "SponsorBlock TV ready — pairing code ${pairing.pairingCode}")
    update("Ready to cast · code ${pairing.pairingCode.spaced()}")

    // Long-poll the bind channel; each videoId the user casts is stashed for the skip pipeline.
    runCatching {
      newClient.bindAndCapture { videoId ->
        Log.i(TAG, "captured cast videoId=$videoId")
        CastCapture.videoId = videoId
        update("Got video $videoId — disconnect to skip")
      }
    }.onFailure { Log.w(TAG, "bind channel ended", it) }
  }

  override fun onDestroy() {
    client?.stop()
    receiverJob?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  // ---------- notification ----------

  private fun update(text: String) =
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

  private fun notification(text: String): Notification {
    ensureChannel()
    return Notification
      .Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_menu_upload)
      .setContentTitle(Config.CAST_DEVICE_NAME)
      .setContentText(text)
      .setOngoing(true)
      .build()
  }

  private fun ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Casting device", NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  private fun String.spaced() = replace(Regex("(\\d{3})(?=\\d)"), "$1 ")

  /** Notification and service constants. */
  companion object {
    private const val TAG = "CastReceiver"
    private const val CHANNEL = "cast_receiver"
    private const val NOTIF_ID = 43

    /** Idempotent: start the explicitly enabled fake TV receiver. */
    fun start(context: Context) {
      context.startForegroundService(Intent(context, CastReceiverService::class.java))
    }

    /** Stop the fake TV and forget its pairing. */
    fun stop(context: Context) {
      context.stopService(Intent(context, CastReceiverService::class.java))
      CastCapture.pairingCode = null
    }
  }
}
