package dev.sponsorblock.companion.cast

/**
 * Bridges the cast receiver and the rest of the app.
 *  - [videoId]: the next videoId cast to "SponsorBlock TV"; the notification listener consumes this
 *    exact id (instead of resolving title+channel) after the user disconnects and playback returns
 *    local. It is deliberately one-shot: keeping an old id would fetch the previous track's
 *    segments for every later YT Music song.
 *  - [pairingCode]: the current "Link with TV code", surfaced in the UI for easy copy.
 */
object CastCapture {
  @Volatile
  var onCapture: ((String) -> Unit)? = null

  @Volatile
  var videoId: String? = null
    set(value) {
      synchronized(this) {
        field = value
      }
      value?.let { onCapture?.invoke(it) }
    }

  /** Returns and clears the pending exact id so it can only be applied to one local track. */
  fun takeVideoId(): String? =
    synchronized(this) {
      videoId.also { videoId = null }
    }

  @Volatile
  var onPairingCode: ((String?) -> Unit)? = null

  @Volatile
  var pairingCode: String? = null
    set(value) {
      field = value
      onPairingCode?.invoke(value)
    }
}
