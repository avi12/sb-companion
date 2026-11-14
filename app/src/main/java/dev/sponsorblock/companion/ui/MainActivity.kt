package dev.sponsorblock.companion.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.google.android.material.color.DynamicColors
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.R

/**
 * Single-activity flow:
 *   - First run shows a two-page onboarding pager (grant access -> how it works). Granting access
 *     auto-advances to the "how it works" page; "Get started" marks onboarding done.
 *   - After that the main screen shows: (1) auto-skip categories, (2) manage access, (3) server.
 *
 * The Activity only wires the pieces: onboarding, the demo player, and the main screen each live in
 * their own controller.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
  private val config by lazy { Config(this) }

  private val onboardRoot by lazy { findViewById<View>(R.id.onboardRoot) }
  private val mainContainer by lazy { findViewById<View>(R.id.mainContainer) }

  private val demo by lazy {
    DemoPlayerController(
      activity = this,
      rootFrame = findViewById(R.id.rootFrame),
      expandRoot = findViewById(R.id.demoExpandRoot),
      expandPlayer = findViewById<PlayerView>(R.id.demoExpandPlayer)
    )
  }
  private val onboarding by lazy {
    OnboardingController(
      activity = this,
      config = config,
      pager = findViewById(R.id.onboardPager),
      dots = findViewById<ViewGroup>(R.id.onboardDots),
      demo = demo,
      onFinished = { showMain(true) }
    )
  }
  private val mainScreen by lazy { MainScreenController(this, config, mainContainer, demo) }

  override fun onCreate(savedInstanceState: Bundle?) {
    DynamicColors.applyToActivityIfAvailable(this)
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    onboarding.setup()
    mainScreen.setup()

    showMain(config.isOnboardingComplete)

    // Collapse the fullscreen demo on Back before leaving the screen.
    onBackPressedDispatcher.addCallback(this) {
      if (demo.isExpanded) {
        demo.collapse()
      } else {
        isEnabled = false
        onBackPressedDispatcher.onBackPressed()
      }
    }
  }

  override fun onDestroy() {
    demo.release()
    super.onDestroy()
  }

  override fun onStart() {
    super.onStart()
    demo.resumeAll()
  }

  override fun onStop() {
    // Stop the looping demo previews from decoding video in the background (battery).
    demo.pauseAll()
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    mainScreen.onResume()
    onboarding.onResume()
  }

  private fun showMain(main: Boolean) {
    onboardRoot.visibility =
      if (main) {
        View.GONE
      } else {
        View.VISIBLE
      }
    mainContainer.visibility =
      if (main) {
        View.VISIBLE
      } else {
        View.GONE
      }
  }
}
