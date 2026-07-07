package dev.sponsorblock.companion

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sponsorblock.companion.media.SkipEngine
import dev.sponsorblock.companion.model.Segment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * DETERMINISTIC emulator verification of the core capability — no YouTube, no login, no network.
 *
 * It stands up a real android.media.session.MediaSession (as YouTube's own player would),
 * advertises ACTION_SEEK_TO, reports "playing at ~5 s", then runs the SkipEngine over that
 * session's MediaController with a single 5–10 s sponsor segment. The session's onSeekTo callback
 * is the ground truth: if the engine works, playback is seeked to ~10.3 s.
 *
 * Run:  ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SkipEngineInstrumentedTest {

    @Test
    fun seeksPastSegmentWhenPlayingInsideIt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        val seekedTo = AtomicLong(-1)

        val thread = HandlerThread("session").apply { start() }
        val handler = Handler(thread.looper)

        lateinit var session: MediaSession
        handler.post {
            session = MediaSession(ctx, "sb-test").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onSeekTo(pos: Long) {
                        seekedTo.set(pos)
                        latch.countDown()
                    }
                }, handler)
                setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Test Video")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test Channel")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 600_000L)
                        .build(),
                )
                setPlaybackState(
                    PlaybackState.Builder()
                        .setState(
                            PlaybackState.STATE_PLAYING,
                            5_000L,               // position: 5.0 s (inside the 5–10 s segment)
                            1.0f,
                            SystemClock.elapsedRealtime(),
                        )
                        .setActions(PlaybackState.ACTION_SEEK_TO)
                        .build(),
                )
                isActive = true
            }
        }
        Thread.sleep(300) // let the session publish

        val engine = SkipEngine(controller = session.controller, handler = handler)
        handler.post {
            engine.attach()
            engine.setSegments(
                listOf(Segment(5.0, 10.0, "sponsor", "skip", "uuid-1", autoSkip = true)),
            )
        }

        assertTrue("seekTo was never called — the engine did not skip", latch.await(5, TimeUnit.SECONDS))
        val target = seekedTo.get()
        assertTrue("seek target $target ms should be just past the 10 s segment end", target in 10_000L..11_000L)

        handler.post {
            engine.detach()
            session.release()
        }
        thread.quitSafely()
    }
}
