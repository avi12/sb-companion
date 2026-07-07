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
enum class Category(
  val id: String,
  val color: Long,
  val defaultMode: SkipMode,
  val displayName: String,
  val description: String,
) {
  SPONSOR("sponsor", 0xFF00d400, SkipMode.AUTO, "Sponsor", "Paid promotion, paid referrals, direct ads"),
  SELFPROMO(
    "selfpromo",
    0xFFFFFF00,
    SkipMode.DISABLED,
    "Self-promotion",
    "Unpaid or self-promotion, merch, donations"
  ),
  INTERACTION(
    "interaction",
    0xFFCC00FF,
    SkipMode.DISABLED,
    "Interaction reminder",
    "\"Like, subscribe, comment\" reminders"
  ),
  INTRO("intro", 0xFF00FFFF, SkipMode.DISABLED, "Intro", "Intro animation or pause before content"),
  OUTRO("outro", 0xFF0202ED, SkipMode.DISABLED, "Outro", "Credits or end cards"),
  PREVIEW("preview", 0xFF008FD6, SkipMode.DISABLED, "Preview / recap", "Recap of earlier or upcoming content"),
  HOOK("hook", 0xFF395699, SkipMode.DISABLED, "Hook", "Preview of what's later in the video"),
  FILLER("filler", 0xFF7300FF, SkipMode.DISABLED, "Filler tangent", "Tangential scenes not needed to understand"),
  MUSIC_OFFTOPIC("music_offtopic", 0xFFFF9900, SkipMode.DISABLED, "Non-music", "Non-music sections in music videos"),
  POI_HIGHLIGHT("poi_highlight", 0xFFFF1684, SkipMode.MANUAL, "Highlight", "Jump to the main point of the video"),
  EXCLUSIVE_ACCESS(
    "exclusive_access",
    0xFF008A5C,
    SkipMode.SHOW,
    "Exclusive access",
    "Video showcases a product with free access"
  ),
  CHAPTER("chapter", 0xFFFFD983, SkipMode.SHOW, "Chapter", "Video chapter markers");

  companion object {
    fun byId(id: String): Category? = entries.firstOrNull { it.id == id }
  }
}
