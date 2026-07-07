package dev.sponsorblock.companion.model

/** One SponsorBlock segment. Times are in SECONDS (the SB API unit). */
data class Segment(
    val start: Double,
    val end: Double,
    val category: String,
    val actionType: String,
    val uuid: String,
    /** Resolved from config at fetch time: should this segment be auto-skipped. */
    val autoSkip: Boolean = true,
)

/** Per-category behaviour, mirrors the extension's CategorySkipOption. */
enum class SkipMode { DISABLED, SHOW, MANUAL, AUTO }

/** The 12 SponsorBlock categories, with the extension's colors (for the future seekbar overlay). */
enum class Category(val id: String, val color: Long, val defaultMode: SkipMode) {
    SPONSOR("sponsor", 0xFF00d400, SkipMode.AUTO),
    SELFPROMO("selfpromo", 0xFFFFFF00, SkipMode.DISABLED),
    INTERACTION("interaction", 0xFFCC00FF, SkipMode.DISABLED),
    INTRO("intro", 0xFF00FFFF, SkipMode.DISABLED),
    OUTRO("outro", 0xFF0202ED, SkipMode.DISABLED),
    PREVIEW("preview", 0xFF008FD6, SkipMode.DISABLED),
    HOOK("hook", 0xFF395699, SkipMode.DISABLED),
    FILLER("filler", 0xFF7300FF, SkipMode.DISABLED),
    MUSIC_OFFTOPIC("music_offtopic", 0xFFFF9900, SkipMode.DISABLED),
    POI_HIGHLIGHT("poi_highlight", 0xFFFF1684, SkipMode.MANUAL),
    EXCLUSIVE_ACCESS("exclusive_access", 0xFF008A5C, SkipMode.SHOW),
    CHAPTER("chapter", 0xFFFFD983, SkipMode.SHOW);

    companion object {
        fun byId(id: String): Category? = entries.firstOrNull { it.id == id }
    }
}
