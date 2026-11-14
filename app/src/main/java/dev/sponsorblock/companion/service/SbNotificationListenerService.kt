package dev.sponsorblock.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.media.session.MediaController
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.cast.CastCapture
import dev.sponsorblock.companion.cast.CastReceiverService
import dev.sponsorblock.companion.media.MediaSessionWatcher
import dev.sponsorblock.companion.media.SkipEngine
import dev.sponsorblock.companion.model.Segment
import dev.sponsorblock.companion.net.SponsorBlockClient
import dev.sponsorblock.companion.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The only permission surface: enabling "Notification access" for this service authorizes
 * MediaSessionManager.getActiveSessions() for the whole app. The listener itself does no
 * notification reading — it exists so we can watch other apps' media sessions.
 */
class SbNotificationListenerService : NotificationListenerService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val config by lazy { Config(this) }
  private val sb by lazy { SponsorBlockClient(config.server) }
  private val fetcher by lazy { FetchOrchestrator(scope, config, sb) { engine } }

  private var watcher: MediaSessionWatcher? = null
  private var engine: SkipEngine? = null
  private var controller: MediaController? = null

  override fun onListenerConnected() {
    ensureChannel()
    // Notification access is now active — stand up the on-phone "SponsorBlock TV" cast receiver
    // (unless the user uninstalled it from the main screen).
    if (config.castTvInstalled) {
      CastReceiverService.start(this)
    }
    // A freshly captured cast videoId should (re)fetch even if the local metadata didn't change.
    // LoungeClient invokes this from its IO worker; serialize fetch state changes on this
    // service's main scope with the MediaController callbacks.
    CastCapture.onCapture = { scope.launch { fetcher.launchFetch() } }
    val component = ComponentName(this, SbNotificationListenerService::class.java)
    watcher =
      MediaSessionWatcher(this, config.targetPackages, component) { c ->
        onActiveController(c)
      }.also { it.start() }
  }

  override fun onListenerDisconnected() {
    CastCapture.onCapture = null
    watcher?.stop()
    // Without notification access there is no local session to consume a captured id, so do not
    // leave the opt-in Lounge long-poll running in the background.
    CastReceiverService.stop(this)
    engine?.detach()
    engine = null
    controller = null
    OverlayState.clear()
  }

  // We don't consume posted notifications; media discovery is via MediaSessionManager.
  override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

  private fun onActiveController(c: MediaController?) {
    android.util.Log.i("SBco", "active session: ${c?.packageName ?: "none"}")
    if (c?.sessionToken == controller?.sessionToken) {
      return
    }
    engine?.detach()
    controller = c
    fetcher.reset()
    OverlayState.clear()
    if (c == null) {
      engine = null
      return
    }
    engine =
      SkipEngine(
        controller = c,
        onSkip = { seg -> postSkipNotice(seg) },
        onNewVideo = { title, artist, durMs -> fetcher.onNewVideo(title, artist, durMs) },
      ).also { it.attach() }
  }

  private fun postSkipNotice(seg: Segment) {
    val n =
      Notification
        .Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_ff)
        .setContentTitle("Skipped ${seg.category}")
        .setContentText("${seg.start.toInt()}s → ${seg.end.toInt()}s")
        .setTimeoutAfter(4000)
        .setOnlyAlertOnce(true)
        .build()
    (getSystemService(NotificationManager::class.java)).notify(NOTICE_ID, n)
  }

  private fun ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Skip notices", NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  /** Notification constants. */
  companion object {
    private const val CHANNEL = "skips"
    private const val NOTICE_ID = 42
  }
}
