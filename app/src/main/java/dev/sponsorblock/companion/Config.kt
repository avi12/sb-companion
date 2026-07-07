package dev.sponsorblock.companion

import android.content.Context
import dev.sponsorblock.companion.model.Category
import dev.sponsorblock.companion.model.SkipMode
import dev.sponsorblock.companion.net.SponsorBlockClient

/** Persisted, read-only-client configuration. Mirrors the extension's relevant keys. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("sb_companion", Context.MODE_PRIVATE)

    val targetPackages: Set<String>
        get() = prefs.getStringSet(KEY_TARGETS, DEFAULT_TARGETS) ?: DEFAULT_TARGETS

    var server: String
        get() = prefs.getString(KEY_SERVER, SponsorBlockClient.DEFAULT_SERVER)!!
        set(v) = prefs.edit().putString(KEY_SERVER, v).apply()

    /** Debug/test escape hatch: force a videoID so the skip engine can be verified without a resolver. */
    var manualVideoId: String?
        get() = prefs.getString(KEY_MANUAL_ID, null)?.takeIf { it.length == 11 }
        set(v) = prefs.edit().putString(KEY_MANUAL_ID, v).apply()

    fun mode(category: Category): SkipMode =
        SkipMode.valueOf(prefs.getString(key(category), category.defaultMode.name)!!)

    fun setMode(category: Category, mode: SkipMode) =
        prefs.edit().putString(key(category), mode.name).apply()

    fun isAutoSkip(categoryId: String): Boolean =
        Category.byId(categoryId)?.let { mode(it) == SkipMode.AUTO } ?: false

    /** Categories to request from the server (anything not fully disabled). */
    val enabledCategories: List<String>
        get() = Category.entries.filter { mode(it) != SkipMode.DISABLED }.map { it.id }

    private fun key(c: Category) = "mode_${c.id}"

    companion object {
        private const val KEY_TARGETS = "targets"
        private const val KEY_SERVER = "server"
        private const val KEY_MANUAL_ID = "manual_video_id"
        val DEFAULT_TARGETS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
        )
    }
}
