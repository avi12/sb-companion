package dev.sponsorblock.companion.cast

/**
 * Bridges the cast receiver and the rest of the app.
 *  - [videoId]: the last videoId cast to "SponsorBlock TV"; the notification listener uses this exact
 *    id (instead of resolving title+channel) after the user disconnects and playback returns local.
 *  - [pairingCode]: the current "Link with TV code", surfaced in the UI for easy copy.
 */
object CastCapture {
  @Volatile
  var onCapture: ((String) -> Unit)? = null

  @Volatile
  var videoId: String? = null
    set(value) {
      field = value
      value?.let { onCapture?.invoke(it) }
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
