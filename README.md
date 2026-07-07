# SponsorBlock Companion (Android, stock app, no root)

Adds SponsorBlock auto-skip to the **unmodified** stock YouTube / YT Music Android apps — no root, no
patched APK. It reads the app's `MediaSession` via a notification listener, gets the video ID,
fetches segments from the SponsorBlock server, and seeks past them with `transportControls.seekTo()`.

**Status:** the skip engine is built and verified (instrumented test + a live intro-skip on real
YouTube). Video-ID acquisition (resolver + cast-grab) and the seekbar overlay are the remaining work.

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
