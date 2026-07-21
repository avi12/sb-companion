# YouTube Music seekbar — reverse-engineering notes (v9.26.51)

Extracted from the on-device base APK (`com.google.android.apps.youtube.music`
9.26.51, versionCode 92651240), pulled with `adb pull`, plus a live
`uiautomator dump` of the running player. Method mirrors
`youtube-seekbar-reversing.md`. These are the facts the seekbar overlay's
node-matching must rely on — and they differ from stock YouTube in one
important way (see **Runtime exposure**).

Reproduce:
```bash
adb -s <device> pull \
  $(adb -s <device> shell pm path com.google.android.apps.youtube.music | grep base | cut -d: -f2) \
  ytmusic-base.apk
aapt2 dump resources ytmusic-base.apk | grep -i 'id/time_bar'
unzip -o ytmusic-base.apk 'classes*.dex' -d dex
grep -aoiE 'L[a-z0-9/_]*TimeBar[a-z0-9/_]*;' dex/*.dex | sort -u
# live truth:
adb -s <device> shell uiautomator dump /sdcard/u.xml && adb pull /sdcard/u.xml
```

## The scrubber view

YT Music draws the timeline with its own custom view class (not YouTube's
`InlineTimeBarWrapper`):

```
com.google.android.apps.youtube.music.watchpage.MusicPlaybackControlsTimeBar
```

It extends the shared player-library base
`com.google.android.libraries.youtube.player.features.overlay.timebar.TimeBar`
(present in classes{,3,4}.dex — the same TimeBar hierarchy the main YouTube app
uses under the hood).

## Resource ids (names not obfuscated)

| Name                      | id (hex)    | what it is                              |
|---------------------------|-------------|-----------------------------------------|
| `time_bar`                | 0x7f0b0d30  | the scrubber view id (set in code)      |
| `time_bar_container`      | 0x7f0b0d31  | RelativeLayout wrapping the scrubber    |
| `time_bar_current_time`   | 0x7f0b0d32  | elapsed-time label                      |
| `time_bar_total_time`     | 0x7f0b0d34  | duration label                          |
| `time_bar_reference_view` | 0x7f0b0d33  | bare `Space` marking scrubbable extent  |

`time_bar_reference_view` is the exact left→right track extent, same trick as
YouTube — and same limitation: a `Space` is not accessibility-exposed, so we
can't read it. Use the SeekBar node bounds instead.

## Runtime exposure — the key difference from YouTube

On YouTube the wrapper node carries a `viewIdResourceName` we can match on. On
**YT Music it does not.** The live `uiautomator` dump of the full "Now Playing"
player shows:

```
node class="android.widget.RelativeLayout"
     resource-id=".../time_bar_container"  bounds=[0,954][2298,1080]
  └─ node class="android.widget.SeekBar"
        resource-id=""            <-- EMPTY, no id on the scrubber itself
        content-desc="41 minutes 32 seconds of 1 hour 13 minutes 40 seconds"
        focusable=true  bounds=[0,954][2424,1080]
```

`MusicPlaybackControlsTimeBar` reports its accessibility className as
`android.widget.SeekBar` and exposes **no** `viewIdResourceName`. The mini-player
bar is identical (bare `SeekBar`, rich `content-desc`, no id).

So YouTube's "match `viewIdResourceName` ends with an id" rule does not fire
here. Matching strategy for YT Music, in order:

1. `className == android.widget.SeekBar` **and** node has a `RangeInfo`
   (SeekBar always exposes one) — this is the scrubber, else
2. its parent's `viewIdResourceName` ends with `time_bar_container`, else
3. a wide-and-short `RangeInfo` node near the bottom (geometry sanity filter),
   optionally confirmed by a `content-desc` matching `… of …` (elapsed of total).

Use the bare `SeekBar` to identify the scrubber, then paint against its
`time_bar_container` parent when present. On the live player the `SeekBar` bounds
run through the right-side system/control inset (`[0..2424]`), while the container
ends at the real drawable track extent (`[0..2298]`). Painting against the wider
node compresses and shifts every SponsorBlock marker to the right.

## Accessibility / seek support (confirmed)

String resources present in the APK: `accessibility_player_progress_time`,
`accessibility_scrubbed_preview`, `accessibility_fast_forward`,
`accessibility_rewind`. The node is `focusable` and carries a `RangeInfo`, so it
is a real seek control.

Note the companion app **seeks via `MediaSession.transportControls.seekTo()`**,
not via the a11y node — the SeekBar node is used only for its bounds (overlay
painting) and, if needed, current-position/duration from the `content-desc` or
`RangeInfo`. No node-level seek action is required.
