# SponsorBlock Companion (Android, stock app, no root)

Adds SponsorBlock auto-skip to the **unmodified** stock YouTube / YT Music Android apps — no root, no
patched APK. It reads the app's `MediaSession` via a notification listener, gets the video ID,
fetches segments from the SponsorBlock server, and seeks past them with `transportControls.seekTo()`.

**Status:** working end-to-end on real devices (Pixel 10 + emulator) for both **stock YouTube and
YT Music**: the skip engine (instrumented test + live skips), videoID resolution, segment fetch, and
the seekbar overlay that paints SponsorBlock segments on the player's scrubber.

![Seekbar overlay painting SponsorBlock segments on the stock YouTube scrubber — a blue preview
marker at the start and a yellow self-promo marker at ~18%, shown zoomed below the
player.](docs/img/seekbar-overlay-demo.png)

*The overlay is drawn by an AccessibilityService in a `TYPE_ACCESSIBILITY_OVERLAY` window; it paints
only while the player controls (and thus the scrubber) are on screen. See
`docs/youtube-seekbar-reversing.md` and `docs/ytmusic-seekbar-reversing.md` for the node-matching
reverse-engineering per app.*

## Start here
- **`docs/RESEARCH.md`** — everything learned + done, exact reproduction commands, and next steps. Read this first.
- **`docs/research.html`** — the visual version (diagrams, RE evidence, screenshots).
- **`HANDOFF.md`** — build + emulator-verify protocol.

## Build & run
```bash
JAVA_HOME=<a JDK 17> ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd notification allow_listener \
  dev.sponsorblock.companion/dev.sponsorblock.companion.service.SbNotificationListenerService
```
Requires a JDK 17 (the Android Gradle Plugin does not run on JDK 24). Open the app → grant
notification access → toggle categories. For now, the debug "manual videoID" field forces a video so
the skip engine can be exercised without the (still stubbed) resolver.

## Layout
```
app/                     the Android app (package dev.sponsorblock.companion)
  src/main/java/.../media/SkipEngine.kt        the core skip loop
  src/main/java/.../service/...ListenerService  orchestrator
  src/androidTest/...SkipEngineInstrumentedTest.kt   deterministic emulator proof
tools/fakescreen/        the cast-grab proof-of-concept (fake YouTube Lounge screen)
docs/                    RESEARCH.md + research.html
```

## Verify
```bash
# deterministic, no YouTube needed:
JAVA_HOME=<jdk17> ./gradlew connectedAndroidTest   # SkipEngineInstrumentedTest
```
See `docs/RESEARCH.md` for the live stock-YouTube and cast-grab reproduction steps.
