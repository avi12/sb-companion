package dev.sponsorblock.companion.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.R
import dev.sponsorblock.companion.cast.CastReceiverService

/**
 * Drives the first-run onboarding pager: grant access -> install SponsorBlock TV -> how it works.
 * Granting access auto-advances from the first page; "Get started" on the last page marks
 * onboarding done and invokes [onFinished].
 */
class OnboardingController(
  private val activity: AppCompatActivity,
  private val config: Config,
  private val pager: ViewPager2,
  private val dots: ViewGroup,
  private val demo: DemoPlayerController,
  private val onFinished: () -> Unit
) {
  fun setup() {
    pager.adapter = OnboardAdapter()
    buildDots(3)
    pager.registerOnPageChangeCallback(
      object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = updateDots(position)
      }
    )
    updateDots(0)
  }

  /** Once access is granted on the first page, glide to "how it works". */
  fun onResume() {
    if (!config.onboardingComplete && activity.hasNotificationAccess() && pager.currentItem == 0) {
      pager.setCurrentItem(1, true)
    }
  }

  private inner class OnboardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 3

    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(
      parent: ViewGroup,
      viewType: Int
    ): RecyclerView.ViewHolder {
      val layout =
        when (viewType) {
          0 -> R.layout.page_onboard_grant
          1 -> R.layout.page_onboard_install
          else -> R.layout.page_onboard_howto
        }
      val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
      return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(
      holder: RecyclerView.ViewHolder,
      position: Int
    ) {
      when (position) {
        0 -> {
          holder.itemView
            .findViewById<MaterialButton>(R.id.grantButton)
            .setOnClickListener { activity.openNotificationSettings() }
        }

        1 -> {
          holder.itemView
            .findViewById<MaterialButton>(R.id.installButton)
            .setOnClickListener { view ->
              config.castTvInstalled = true
              CastReceiverService.start(activity)
              (view as MaterialButton).text = "SponsorBlock TV ready"
              view.isEnabled = false
              pager.setCurrentItem(2, true)
            }
        }

        else -> {
          holder.itemView.findViewById<TextView>(R.id.step2Text).text =
            "Tap Cast and pick “${Config.CAST_DEVICE_NAME}”"
          demo.bind(holder.itemView, R.id.demoPlayer, R.id.demoMock)
          holder.itemView
            .findViewById<MaterialButton>(R.id.startButton)
            .setOnClickListener {
              config.onboardingComplete = true
              onFinished()
            }
        }
      }
    }
  }

  private fun buildDots(count: Int) {
    dots.removeAllViews()
    val size = (8 * activity.resources.displayMetrics.density).toInt()
    val margin = (4 * activity.resources.displayMetrics.density).toInt()
    repeat(count) {
      val dot = View(activity)
      dot.background = activity.getDrawable(R.drawable.hero_circle)
      val lp =
        ViewGroup.MarginLayoutParams(size, size).apply {
          marginStart = margin
          marginEnd = margin
        }
      dots.addView(dot, lp)
    }
  }

  private fun updateDots(active: Int) {
    val on = activity.themeColor(com.google.android.material.R.attr.colorPrimary)
    val off = activity.themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    for (i in 0 until dots.childCount) {
      dots.getChildAt(i).tintBackground(if (i == active) on else off)
    }
  }
}
