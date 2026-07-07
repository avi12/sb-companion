package dev.sponsorblock.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.media.session.MediaController
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.media.MediaSessionWatcher
import dev.sponsorblock.companion.media.SkipEngine
import dev.sponsorblock.companion.model.Segment
import dev.sponsorblock.companion.net.SponsorBlockClient
import dev.sponsorblock.companion.net.VideoIdResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

  private var watcher: MediaSessionWatcher? = null
  private var engine: SkipEngine? = null
  private var controller: MediaController? = null

  @Volatile private var currentMeta: Triple<String, String?, Long>? = null
  private var lastFetchedId: String? = null
  private var fetchJob: Job? = null

  override fun onListenerConnected() {
    ensureChannel()
    // Notification access is now active — stand up the on-phone "SponsorBlock TV" cast receiver
    // (unless the user uninstalled it from the main screen).
    if (config.castTvInstalled) {
      dev.sponsorblock.companion.cast.CastReceiverService
        .start(this)
    }
    // A freshly captured cast videoId should (re)fetch even if the local metadata didn't change.
    dev.sponsorblock.companion.cast.CastCapture.onCapture = { launchFetch() }
    val component = ComponentName(this, SbNotificationListenerService::class.java)
    watcher =
      MediaSessionWatcher(this, config.targetPackages, component) { c ->
        onActiveController(c)
      }.also { it.start() }
  }

  override fun onListenerDisconnected() {
    dev.sponsorblock.companion.cast.CastCapture.onCapture = null
    watcher?.stop()
    engine?.detach()
    engine = null
    controller = null
  }

  // We don't consume posted notifications; media discovery is via MediaSessionManager.
  override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

  private fun onActiveController(c: MediaController?) {
    if (c?.sessionToken == controller?.sessionToken) {
      return
    }
    engine?.detach()
    controller = c
    currentMeta = null
    lastFetchedId = null
    if (c == null) {
      engine = null
      return
    }
    engine =
      SkipEngine(
        controller = c,
        onSkip = { seg -> postSkipNotice(seg) },
        onNewVideo = { title, artist, durMs -> onNewVideo(title, artist, durMs) },
      ).also { it.attach() }
  }

  private fun onNewVideo(
    title: String?,
    artist: String?,
    durationMs: Long
  ) {
    if (title.isNullOrBlank()) {
      return
    }
    currentMeta = Triple(title, artist, durationMs)
    launchFetch()
  }

  /** Resolves the videoId (captured cast id first) and fetches its segments for the local session. */
  private fun launchFetch() {
    val (title, artist, _) = currentMeta ?: return
    fetchJob?.cancel()
    fetchJob =
      scope.launch {
        val videoId =
          dev.sponsorblock.companion.cast.CastCapture.videoId
            ?: config.manualVideoId
            ?: VideoIdResolver.resolve(title, artist.orEmpty())
            ?: return@launch
        if (videoId == lastFetchedId) {
          return@launch
        }
        lastFetchedId = videoId
        engine?.setSegments(emptyList()) // clear stale segments immediately
        val segments =
          runCatching {
            sb.fetch(videoId, config.enabledCategories, listOf("skip"))
          }.getOrDefault(emptyList())
            .map { it.copy(autoSkip = config.isAutoSkip(it.category)) }
        engine?.setSegments(segments)
      }
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

  companion object {
    private const val CHANNEL = "skips"
    private const val NOTICE_ID = 42
  }
}
