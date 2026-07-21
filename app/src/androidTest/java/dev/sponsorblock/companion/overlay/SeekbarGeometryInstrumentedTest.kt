package dev.sponsorblock.companion.overlay

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekbarGeometryInstrumentedTest {
  @Test
  fun trimsYtMusicTimeLabelsFromTheDrawableTrack() {
    val track =
      SeekbarAccessibilityService.trimYtMusicTimeLabels(
        container = Rect(0, 954, 2298, 1080),
        elapsedLabel = Rect(32, 954, 112, 1080),
        totalLabel = Rect(2218, 954, 2298, 1080),
        labelGapPx = 24,
      )

    assertEquals(Rect(136, 954, 2194, 1080), track)
  }

  @Test
  fun trimsTheFlattenedFullscreenYtMusicSeekbar() {
    val track =
      SeekbarAccessibilityService.trimUnlabeledYtMusicTrack(
        Rect(0, 954, 2424, 1080),
      )

    assertEquals(Rect(134, 954, 2196, 1080), track)
  }
}
