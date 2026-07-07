# YouTube seekbar — reverse-engineering notes (v21.26.364)

Extracted from `youtube-21-26-364-secondary.apk` with `aapt2 dump` (resource table
+ compiled layout xmltrees) and raw dex string greps. These are the facts the
seekbar overlay's node-matching relies on.

## The scrubber view

All player contexts draw the timeline with the **same** custom view class:

```
com.google.android.apps.youtube.app.common.player.overlay.InlineTimeBarWrapper
```

It carries a **different resource id per context** (resource *names* are not
obfuscated in the APK, so these strings surface verbatim as
`viewIdResourceName` on the accessibility node):

| Context                     | id (name)                    | hex         |
|-----------------------------|------------------------------|-------------|
| Watch page / inline         | `inline_playback_time_bar`   | 0x7f0b09d8  |
| Fullscreen (large controls) | `large_controls_time_bar`    | 0x7f0b0a42  |
| Floaty bar / minibar        | `floaty_bar_time_bar_view`   | 0x7f0b082a  |
| Generic / misc              | `time_bar`                   | 0x7f0b166b  |
| Player seekbar alias        | `player_seekbar`             | 0x7f0b0f28  |

Layouts confirming the class↔id mapping: `watch_while_time_bar` (res/6BA),
`youtube_controls_bottom_ui_container` (res/QYO), `large_controls_scrubber_ui_container`
(res/otA), `floaty_bar_time_bar` (res/e8F).

## Geometry

The bottom UI container also has `time_bar_reference_view` (0x7f0b1674) — a bare
`Space` that marks the exact scrubbable extent (left→right of the drawable track).
It is **not** accessibility-exposed (a Space has no text / not focusable), so we
can't read it directly; we use the `InlineTimeBarWrapper` node bounds instead,
which span the same horizontal range.

## Accessibility exposure (why the overlay can find it)

The time bar is a seek control with accessibility support — confirmed by:
- `onInitializeAccessibilityNodeInfo` overrides present in dex (6×), and
- string resources `accessibility_seek_backward` / `_forward`,
  `accessibility_scrubbed_preview`, `accessibility_scrubber_close_button`.

So the wrapper node exposes seek actions + a RangeInfo, and its
`getBoundsInScreen()` gives the track rectangle. Matching strategy, in order:
1. `viewIdResourceName` ends with one of the ids above, else
2. `className` contains `TimeBar`, else
3. a `RangeInfo` node that is wide-and-short (geometry sanity filter).
