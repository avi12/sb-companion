# SponsorBlock Companion — Research & Status (resume here)

Read this first if you're picking up the project. It consolidates everything learned and done so
far, with confidence levels, exact reproduction commands, and the remaining work. The visual
version (diagrams, screenshots) is `docs/research.html`.

---

## TL;DR — status

**Goal:** add SponsorBlock auto-skip to the **unmodified** stock Android YouTube / YT Music apps —
no root, no patched APK. Read-only (fetch + skip; no submitting/voting).

| Piece | Status |
|---|---|
| Core **skip engine** (observe session → `seekTo` past segments) | ✅ built + verified on emulator (instrumented test **and** a live intro-skip on real YouTube) |
| **App scaffold** (Kotlin, notification-listener, fetch, config UI) | ✅ builds clean → `app-debug.apk` (12.9 MB) |
| **Video-ID acquisition** | ⚠️ two paths designed; resolver stubbed, cast-grab **demonstrated live** but not yet wired into the app |
| Title→ID **resolver** hardening | ⛔ TODO (phase 2) |
| **Cast-grab** integration into the app | ⛔ TODO (proof-of-concept works standalone at `tools/fakescreen/`) |
| **Seekbar overlay** (paint segments) | ⛔ TODO (phase 2, cosmetic) |
| **YT Music** support | ⛔ TODO (mechanism should be identical; not tested) |

**The one thing that matters most and is DONE:** skipping works. Given a videoID + segments, the app
seeks past segments on the stock YouTube session. Everything else is about getting the videoID.

---

## The hard problem (why this project is non-trivial)

The stock YouTube app **does not expose the video ID** through any surface a no-root app can read.
This was verified by decompiling YouTube 21.26.364 (see "Verified facts"). So the app must get the
ID another way:

1. **Title + channel → search** (the resolver). Automatic, silent, but a *best guess* — can pick a
   re-upload or the wrong ATV/OMV twin; fails on user uploads. Must validate by duration and fail closed.
2. **Cast-grab** (the exact-ID path). The user casts to a fake receiver we run; it captures the exact
   videoID from the Lounge stream. Reliable ID, but casting moves playback off the phone, so it's an
   opt-in per-video trigger, best for cases the resolver gets wrong (esp. YT Music).

Design: resolver is the silent default; cast-grab is the opt-in "get exact ID" action.

---

## Verified facts (from decompiling YouTube 21.26.364 — trust these)

Full RE was done with androguard + jadx. Key load-bearing findings:

| Fact | Evidence | Confidence |
|---|---|---|
| Session metadata = TITLE, ARTIST(=channel), ALBUM_ARTIST, DURATION, ALBUM_ART, custom video w/h px — **no MEDIA_ID / MEDIA_URI** | builder `aqpv.m()`; whole-tree search finds no ID write | Certain |
| Session **extras**, **playbackstate extras**, **queue** all empty/unset | `aqpw` impls return `new Bundle()`; `setQueue` not wired | Certain |
| PlaybackState publishes **position + updateTime + speed** | `fh.d(state, pos, speed)` | Certain |
| Session **honors external `onSeekTo`** → seeks the real player | `kwr extends ew`; `kwr.g(long)` is the onSeekTo override | Certain |
| `ACTION_SEEK_TO` (256) advertised when seekable | `aqpv`: `if (seekable) actions \|= 256` | Certain |
| The videoID lives in an obfuscated `PlayerResponseModel` field (what ReVanced hooks); anchor strings present in this APK | `"Failed to download video (IllegalStateException): %s"`, `"Media progress reported outside media playback: "` | Certain |
| MediaBrowserService gives nothing (YT: empty tree, root for all but SystemUI; YT Music: rejects non-Google callers) | `MainAppMediaBrowserService.b()/c()` | Certain |
| App is a **sender/remote only** in Lounge/DIAL/Cast — never a screen; videoID only leaves via casting (outbound) or share | `aluk` (setVideo outgoing / nowPlaying incoming), `almz` (DIAL M-SEARCH client), no self-screen registration | Certain |
| Native **Google Cast** fake-receiver is blocked by device-cert `AUTH_CHALLENGE`; **DIAL+Lounge** path is cert-free | `AUTH_CHALLENGE` ×4 in dex; yt-cast-receiver works without a cert | Certain |

Prior art that already skips on stock YouTube via `seekTo`: **Sponsor Skip** (codeberg jaival/Sponsor-Skip),
**alex-kuksa/SponsorBlockAndroid**. iSponsorBlockTV is the *TV* case (Lounge remote). ReVanced is the
patched-APK case.

---

## Architecture / pipeline

```
stock YouTube app ──(MediaSession)──► NotificationListenerService (getActiveSessions)
                                            │  title, channel, duration, live position
                                            ▼
                                   video-ID acquisition
                                     ├─ resolver: title+channel → search → id  (default, lossy)
                                     └─ cast-grab: fake screen captures exact id (opt-in)
                                            │ id
                                            ▼
                                   SponsorBlockClient  GET /api/skipSegments/{sha256(id)[0..5]}
                                            │ segments[]
                                            ▼
                                   SkipEngine  ── transportControls.seekTo(segEnd) ──► back into the app
```

File map (`app/src/main/java/dev/sponsorblock/companion/`):
- `media/SkipEngine.kt` — extrapolate position → schedule → verify → `seekTo` (the core)
- `media/MediaSessionWatcher.kt` — `getActiveSessions` gated by the notif-listener component
- `net/SponsorBlockClient.kt`, `net/Hashing.kt` — hash-prefix fetch, 404 = no segments
- `net/VideoIdResolver.kt` — title+channel search scrape (+ duration check) — **stub, needs hardening**
- `service/SbNotificationListenerService.kt` — orchestrator (watch → acquire id → fetch → engine → notices)
- `Config.kt`, `model/Models.kt` — categories/modes/server/manual-id; `ui/MainActivity.kt` — settings UI
- `app/src/androidTest/.../SkipEngineInstrumentedTest.kt` — deterministic emulator proof

---

## The cast-grab mechanism (works — see `tools/fakescreen/`)

A ~40-line Node fake YouTube **screen** (`yt-cast-receiver`) that registers as a `LOUNGE_SCREEN` and
captures the videoID the phone casts to it. **Demonstrated live**: entered its TV code in the
emulator's YouTube, it captured a videoID we never knew in advance and fetched its SB segments.

Run it:
```bash
cd tools/fakescreen && npm install
node capture.mjs          # prints a "Link with TV code" TV code; logs videoId on doPlay(video)
```
Key facts:
- **Discovery is dead on the emulator** (Google docs: no IGMP/multicast; SLIRP NAT), and Windows has
  no TAP/bridge. So use **"Link with TV code"** — a cloud handshake via `youtube.com/api/lounge`,
  NAT-independent. That's what the fake screen's `getPairingCodeRequestService()` provides.
- `yt-cast-receiver` is the right tool because it uses `get_pairing_code` (screen *displays* a code
  for the phone to enter). `plaincast` uses the reverse direction and won't work here.
- Capture point: `CapturePlayer.doPlay(video)` → `video.id` is the raw 11-char videoID.
- **Caveat that held in practice:** linking transfers playback to the fake screen (position 0). For
  real use, grab-then-disconnect and skip on the *local* session. This is why cast-grab is an opt-in
  trigger, not the default.

**Not yet done:** wiring the captured ID from the fake screen back into the Android app. Options: run
the receiver logic inside the app (Kotlin port / embedded), or a small local service the app polls.

---

## The skip engine (the algorithm)

Anchor on each PlaybackState update; schedule ONE timer to the next segment; re-verify before firing.
```
onPlaybackStateChanged(s):
  cancelPending()
  if s.state != PLAYING: return
  pos = s.position + (now - s.lastPositionUpdateTime) * s.playbackSpeed   # extrapolate
  seg = firstEnabledSegmentEndingAfter(pos)
  if seg == null: return
  if pos >= seg.start: seekTo(seg.end + EPSILON); return                  # already inside
  scheduleAt((seg.start - pos)/speed):
     if playing and |pos - seg.start| < GUARD: seekTo(seg.end + EPSILON)  # re-verify (ad/seek guard)
     else: reschedule()
```
Edge cases handled/needed: seek latency (land at `end + 300ms`), ads (re-verify + duration-jump
invalidation), Shorts (skip them; seek unreliable), speed≠1 (divide delay by speed), mute categories
(AudioManager mute instead of seek — optional), per-video re-acquire of the ID.

---

## Emulator setup & how to REPRODUCE the verification

Environment on this machine: Android SDK at `%LOCALAPPDATA%\Android\Sdk` (set `ANDROID_HOME`); `adb`
on PATH; AVDs `pixel_playstore` (Play, Android 16), `acfix_cap33` (google_apis, API 33). **JDK 17 is
required** for the build (default Java is 24). A JDK17 + gradle were bootstrapped into the old
scratchpad; `./gradlew` now has a wrapper but still needs `JAVA_HOME=<a JDK 17>`.

### Build
```bash
JAVA_HOME=<jdk17> ./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

### 1) Deterministic core test (no YouTube/login)
```bash
"<SDK>/emulator/emulator.exe" -avd acfix_cap33 -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device   # poll: adb shell getprop sys.boot_completed == 1
JAVA_HOME=<jdk17> ./gradlew connectedAndroidTest # SkipEngineInstrumentedTest asserts seekTo(~10.3s)
```
Result on record: PASS (`tests=1 failures=0`, `seeksPastSegmentWhenPlayingInsideIt`).

### 2) Live skip on real stock YouTube (what proved the intro-skip)
The app has no resolver yet, so use the **debug manual videoID** to force a known video, and make sure
that video is what's actually playing. Reproduction that worked:
```bash
# a) point the app at a video with an EARLY segment and enable that category.
#    SciShow "This Gorgeous Rock Was Made by a Supercontinent" = oZDByvhf3Ik, intro 57.5s -> 61.2s.
adb shell run-as dev.sponsorblock.companion sh -c 'cat > shared_prefs/sb_companion.xml' <<'XML'
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
  <string name="manual_video_id">oZDByvhf3Ik</string>
  <string name="server">https://sponsor.ajay.app</string>
  <string name="mode_intro">AUTO</string>
  <string name="mode_sponsor">AUTO</string>
</map>
XML
# b) restart the service so it reloads config
comp=dev.sponsorblock.companion/dev.sponsorblock.companion.service.SbNotificationListenerService
adb shell am force-stop dev.sponsorblock.companion
adb shell cmd notification disallow_listener "$comp"; adb shell cmd notification allow_listener "$comp"
# c) open the SAME video and play (note: the &t=NN start-time param is IGNORED by this app build,
#    and YouTube RESUMES the last video unless you force-stop it first)
adb shell am force-stop com.google.android.youtube
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=oZDByvhf3Ik"
adb shell input keyevent 126     # play
# d) observe: position jumps from ~57s to ~61.5s at the intro (= our seg.end + epsilon)
adb shell dumpsys media_session | grep -oE 'position=[0-9]+'
```
Result on record: playback jumped to **61520 ms** (= intro.end 61.2s + 0.3s epsilon) — the SkipEngine
fired `seekTo`. Gotchas discovered: the `t=NN` deeplink param is ignored; YouTube resumes the last
video unless force-stopped; the manual ID **must** match the actually-playing video.

### 3) Cast-grab demo
```bash
cd tools/fakescreen && npm install && node capture.mjs   # prints a TV code
# In the emulator's YouTube: Cast → "Link with TV code" → enter it → play a video
# -> capture.mjs logs "CAPTURED videoId = ..." + its SponsorBlock segments
```
Result on record: captured `iYlODtkyw_I` (a MrBeast video) + 2 segments, with zero prior knowledge.

---

## What's next (phase 2, roughly in order)

1. **Resolver hardening** — parse `ytInitialData`, require title AND channel exact match, enforce the
   duration cross-check, fail closed. Add YT Music via Innertube (`music.youtube.com/youtubei/v1/search`).
2. **Wire cast-grab into the app** — port/embed the `yt-cast-receiver` capture so the exact ID flows to
   `SponsorBlockClient` → `SkipEngine`, exposed as an opt-in "Get exact ID (cast)" action.
3. **Now-playing card + real config UI** — replace the debug UI; show the matched video + a refetch
   button; per-category modes (Disabled/Show/Manual/Auto), not just on/off.
4. **Manual-skip + mute** — notification "Skip [category]" action for manual categories; AudioManager
   mute option for `music_offtopic`.
5. **Seekbar overlay** — AccessibilityService reads the seekbar node bounds + duration; a
   `TYPE_APPLICATION_OVERLAY` draws marks at `x = left + (segStart/duration)*width`. Cosmetic; fragile
   (re-track on rotation/fullscreen/mini-player). Category colors are in `model/Models.kt`.
6. **YT Music** — same mechanism; verify metadata fields and that `seekTo` is honored on its session.

---

## Reference links

- Prior art (stock YouTube skip): https://codeberg.org/jaival/Sponsor-Skip , https://github.com/alex-kuksa/SponsorBlockAndroid
- TV case: https://github.com/dmunozv04/iSponsorBlockTV ; Lounge lib: https://github.com/FabioGNR/pyytlounge
- Fake screen: https://github.com/patrickkfkan/yt-cast-receiver ; https://github.com/aykevl/plaincast
- Lounge protocol / fake-TV writeup: https://bugs.xdavidhu.me/google/2021/04/05/i-built-a-tv-that-plays-all-of-your-private-youtube-videos/
- Emulator networking (no IGMP/multicast): https://developer.android.com/studio/run/emulator-networking
- ReVanced patches (videoId/time hooks reference): https://github.com/anddea/revanced-patches
- SponsorBlock API: https://wiki.sponsor.ajay.app/w/API_Docs
