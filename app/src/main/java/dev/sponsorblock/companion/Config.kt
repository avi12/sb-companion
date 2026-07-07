package dev.sponsorblock.companion

import android.content.Context
import dev.sponsorblock.companion.model.Category
import dev.sponsorblock.companion.model.SkipMode
import dev.sponsorblock.companion.net.SponsorBlockClient

/** Persisted, read-only-client configuration. Mirrors the extension's relevant keys. */
class Config(
  context: Context
) {
  private val prefs = context.getSharedPreferences("sb_companion", Context.MODE_PRIVATE)

  val targetPackages: Set<String>
    get() = prefs.getStringSet(KEY_TARGETS, DEFAULT_TARGETS) ?: DEFAULT_TARGETS

  var server: String
    get() = prefs.getString(KEY_SERVER, SponsorBlockClient.DEFAULT_SERVER)!!
    set(v) = prefs.edit().putString(KEY_SERVER, v).apply()

  /** Whether the on-phone "SponsorBlock TV" cast receiver should run. Toggled from the main screen. */
  var castTvInstalled: Boolean
    get() = prefs.getBoolean(KEY_TV_INSTALLED, true)
    set(v) = prefs.edit().putBoolean(KEY_TV_INSTALLED, v).apply()

  /** Whether to paint segment bars on the YouTube seekbar (needs the accessibility service on). */
  var seekbarOverlayEnabled: Boolean
    get() = prefs.getBoolean(KEY_OVERLAY, true)
    set(v) = prefs.edit().putBoolean(KEY_OVERLAY, v).apply()

  /** Whether the first-run onboarding (grant + how-it-works) has been completed. */
  var onboardingComplete: Boolean
    get() = prefs.getBoolean(KEY_ONBOARDED, false)
    set(v) = prefs.edit().putBoolean(KEY_ONBOARDED, v).apply()

  /** Stable per-install device id the Lounge receiver advertises. Generated once, then reused. */
  val deviceId: String
    get() =
      prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID
        .randomUUID()
        .toString()
        .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

  /** The screen id from generate_screen_id, reused across restarts so we stay the SAME remembered
   *  cast device (otherwise YouTube shows "SponsorBlock TV 2", "…3" for each fresh screen). */
  var screenId: String?
    get() = prefs.getString(KEY_SCREEN_ID, null)
    set(v) = prefs.edit().putString(KEY_SCREEN_ID, v).apply()

  /** Debug/test escape hatch: force a videoID so the skip engine can be verified without a resolver. */
  var manualVideoId: String?
    get() = prefs.getString(KEY_MANUAL_ID, null)?.takeIf { it.length == 11 }
    set(v) = prefs.edit().putString(KEY_MANUAL_ID, v).apply()

  fun mode(category: Category): SkipMode = SkipMode.valueOf(prefs.getString(key(category), category.defaultMode.name)!!)

  fun setMode(
    category: Category,
    mode: SkipMode
  ) = prefs.edit().putString(key(category), mode.name).apply()

  fun isAutoSkip(categoryId: String): Boolean = Category.byId(categoryId)?.let { mode(it) == SkipMode.AUTO } ?: false

  /** Categories to request from the server (anything not fully disabled). */
  val enabledCategories: List<String>
    get() = Category.entries.filter { mode(it) != SkipMode.DISABLED }.map { it.id }

  private fun key(c: Category) = "mode_${c.id}"

  companion object {
    /** Friendly name the fake cast receiver advertises in YouTube's Cast picker. */
    const val CAST_DEVICE_NAME = "SponsorBlock TV"

    private const val KEY_TARGETS = "targets"
    private const val KEY_SERVER = "server"
    private const val KEY_MANUAL_ID = "manual_video_id"
    private const val KEY_ONBOARDED = "onboarding_complete"
    private const val KEY_OVERLAY = "seekbar_overlay_enabled"
    private const val KEY_TV_INSTALLED = "cast_tv_installed"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SCREEN_ID = "screen_id"
    val DEFAULT_TARGETS =
      setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
      )
  }
}
