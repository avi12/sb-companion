package dev.sponsorblock.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sponsorblock.companion.cast.CastCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastCaptureInstrumentedTest {
  @Test
  fun consumesCapturedVideoIdOnlyOnce() {
    CastCapture.videoId = null
    try {
      CastCapture.videoId = "dQw4w9WgXcQ"

      assertEquals("dQw4w9WgXcQ", CastCapture.takeVideoId())
      assertNull(CastCapture.takeVideoId())
    } finally {
      CastCapture.videoId = null
    }
  }
}
