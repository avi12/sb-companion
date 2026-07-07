package dev.sponsorblock.companion.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager

/**
 * Tracks the active MediaController for the target packages (stock YouTube / YT Music) via
 * MediaSessionManager. getActiveSessions() requires the caller to name an enabled
 * NotificationListenerService component — that is how a normal app is allowed to read other
 * apps' sessions without the privileged MEDIA_CONTENT_CONTROL permission.
 */
class MediaSessionWatcher(
    context: Context,
    private val targetPackages: Set<String>,
    private val listenerComponent: ComponentName,
    private val onActiveController: (MediaController?) -> Unit,
) {
    private val msm =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> emit(controllers) }

    fun start() {
        msm.addOnActiveSessionsChangedListener(listener, listenerComponent)
        emit(msm.getActiveSessions(listenerComponent))
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
    }

    private fun emit(controllers: List<MediaController>?) {
        onActiveController(controllers?.firstOrNull { it.packageName in targetPackages })
    }
}
