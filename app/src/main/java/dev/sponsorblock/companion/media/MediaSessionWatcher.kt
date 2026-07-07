package dev.sponsorblock.companion.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper

/**
 * Tracks the active MediaController for the target packages (stock YouTube / YT Music) via
 * MediaSessionManager. getActiveSessions() requires the caller to name an enabled
 * NotificationListenerService component — that is how a normal app is allowed to read other
 * apps' sessions without the privileged MEDIA_CONTENT_CONTROL permission.
 *
 * The listener grant is not always live the instant onListenerConnected() fires: the system can
 * deliver the connect callback a beat before it commits the enabled-listener record, so the very
 * first getActiveSessions()/addOnActiveSessionsChangedListener() throws
 * "SecurityException: Missing permission to control media." If that propagated it would crash the
 * whole listener service (and kill the skip/fetch pipeline), so start() retries with backoff until
 * the grant is honored.
 */
class MediaSessionWatcher(
  context: Context,
  private val targetPackages: Set<String>,
  private val listenerComponent: ComponentName,
  private val onActiveController: (MediaController?) -> Unit,
) {
  private val msm =
    context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
  private val handler = Handler(Looper.getMainLooper())

  private val listener =
    MediaSessionManager.OnActiveSessionsChangedListener { controllers -> emit(controllers) }

  private var registered = false

  fun start() = tryStart(attempt = 0)

  private fun tryStart(attempt: Int) {
    val ok =
      runCatching {
        msm.addOnActiveSessionsChangedListener(listener, listenerComponent)
        emit(msm.getActiveSessions(listenerComponent))
      }.isSuccess
    if (ok) {
      registered = true
      return
    }
    // Listener grant not committed yet — back off and retry (caps at ~5 s total).
    if (attempt < MAX_RETRIES) {
      handler.postDelayed({ tryStart(attempt + 1) }, RETRY_DELAY_MS)
    }
  }

  fun stop() {
    handler.removeCallbacksAndMessages(null)
    if (registered) {
      runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
      registered = false
    }
  }

  private fun emit(controllers: List<MediaController>?) {
    onActiveController(controllers?.firstOrNull { it.packageName in targetPackages })
  }

  private companion object {
    const val MAX_RETRIES = 10
    const val RETRY_DELAY_MS = 500L
  }
}
