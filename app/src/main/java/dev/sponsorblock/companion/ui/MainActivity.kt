package dev.sponsorblock.companion.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.annotation.OptIn
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
  private lateinit var config: Config

  private lateinit var onboardRoot: View
  private lateinit var mainContainer: View

  private lateinit var demo: DemoPlayerController
  private lateinit var onboarding: OnboardingController
  private lateinit var mainScreen: MainScreenController

  override fun onCreate(savedInstanceState: Bundle?) {
    DynamicColors.applyToActivityIfAvailable(this)
    super.onCreate(savedInstanceState)
    config = Config(this)
    setContentView(R.layout.activity_main)

    onboardRoot = findViewById(R.id.onboardRoot)
    mainContainer = findViewById(R.id.mainContainer)

    demo =
      DemoPlayerController(
        activity = this,
        rootFrame = findViewById(R.id.rootFrame),
        expandRoot = findViewById(R.id.demoExpandRoot),
        expandPlayer = findViewById<PlayerView>(R.id.demoExpandPlayer)
      )
    onboarding =
      OnboardingController(
        activity = this,
        config = config,
        pager = findViewById(R.id.onboardPager),
        dots = findViewById<ViewGroup>(R.id.onboardDots),
        demo = demo,
        onFinished = { showMain(true) }
      )
    mainScreen = MainScreenController(this, config, mainContainer, demo)

    onboarding.setup()
    mainScreen.setup()

    showMain(config.onboardingComplete)

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
