package dev.sponsorblock.companion.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.transition.MaterialContainerTransform
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.R
import dev.sponsorblock.companion.cast.CastCapture
import dev.sponsorblock.companion.cast.CastReceiverService
import dev.sponsorblock.companion.model.Category
import dev.sponsorblock.companion.model.SkipMode

/**
 * Single-activity flow:
 *   - First run shows a two-page onboarding pager (grant access -> how it works). Granting access
 *     auto-advances to the "how it works" page; "Get started" marks onboarding done.
 *   - After that the main screen shows: (1) auto-skip categories, (2) manage access, (3) server.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
  private lateinit var config: Config

  private lateinit var onboardRoot: View
  private lateinit var mainContainer: View
  private lateinit var pager: ViewPager2
  private lateinit var dots: ViewGroup

  private lateinit var rootFrame: ViewGroup
  private lateinit var demoExpandRoot: View
  private lateinit var demoExpandPlayer: PlayerView

  /** Every looping preview player, released together in onDestroy. */
  private val demoPlayers = mutableListOf<ExoPlayer>()

  /** The inline PlayerView currently expanded (so we can morph back to it). */
  private var expandedFrom: PlayerView? = null

  /** res/raw/demo.mp4 as a Media3 rawresource URI, or null if it isn't bundled. */
  private val demoUri: android.net.Uri? by lazy {
    val resId = resources.getIdentifier("demo", "raw", packageName)
    if (resId == 0) {
      null
    } else {
      RawResourceDataSource.buildRawResourceUri(resId)
    }
  }

  private lateinit var accessCard: MaterialCardView
  private lateinit var accessTitle: TextView
  private lateinit var accessBody: TextView
  private lateinit var accessButton: MaterialButton

  override fun onCreate(savedInstanceState: Bundle?) {
    DynamicColors.applyToActivityIfAvailable(this)
    super.onCreate(savedInstanceState)
    config = Config(this)
    setContentView(R.layout.activity_main)

    onboardRoot = findViewById(R.id.onboardRoot)
    mainContainer = findViewById(R.id.mainContainer)
    pager = findViewById(R.id.onboardPager)
    dots = findViewById(R.id.onboardDots)
    rootFrame = findViewById(R.id.rootFrame)
    demoExpandRoot = findViewById(R.id.demoExpandRoot)
    demoExpandPlayer = findViewById(R.id.demoExpandPlayer)

    setupOnboarding()
    setupMainScreen()

    showMain(config.onboardingComplete)

    // Collapse the fullscreen demo on Back before leaving the screen.
    onBackPressedDispatcher.addCallback(this) {
      if (demoExpandRoot.isVisible) {
        collapseDemo()
      } else {
        isEnabled = false
        onBackPressedDispatcher.onBackPressed()
      }
    }
  }

  override fun onDestroy() {
    demoPlayers.forEach { it.release() }
    super.onDestroy()
  }

  override fun onResume() {
    super.onResume()
    refreshAccessState()
    // Once access is granted on the first page, glide to "how it works".
    if (!config.onboardingComplete && hasAccess() && pager.currentItem == 0) {
      pager.setCurrentItem(1, true)
    }
  }

  // ---------- Onboarding ----------

  private fun setupOnboarding() {
    pager.adapter = OnboardAdapter()
    buildDots(3)
    pager.registerOnPageChangeCallback(
      object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = updateDots(position)
      }
    )
    updateDots(0)
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
            .setOnClickListener { openNotificationSettings() }
        }
        1 -> {
          holder.itemView
            .findViewById<MaterialButton>(R.id.installButton)
            .setOnClickListener { view ->
              CastReceiverService.start(this@MainActivity)
              (view as MaterialButton).text = "SponsorBlock TV ready"
              view.isEnabled = false
              pager.setCurrentItem(2, true)
            }
        }
        else -> {
          holder.itemView.findViewById<TextView>(R.id.step2Text).text =
            "Tap Cast and pick “${Config.CAST_DEVICE_NAME}”"
          setupDemoVideo(holder.itemView, R.id.demoPlayer, R.id.demoMock)
          holder.itemView
            .findViewById<MaterialButton>(R.id.startButton)
            .setOnClickListener {
              config.onboardingComplete = true
              showMain(true)
            }
        }
      }
    }
  }

  /**
   * Binds a looping, muted preview player (with native controls) to [playerViewId]. The controls'
   * fullscreen button expands the clip via a Material container transform. No-op if the demo clip
   * isn't bundled, leaving the static mock in place.
   */
  private fun setupDemoVideo(
    page: View,
    playerViewId: Int,
    mockId: Int
  ) {
    val uri = demoUri ?: return
    val playerView = page.findViewById<PlayerView>(playerViewId)
    val mock = page.findViewById<View>(mockId)

    val player =
      ExoPlayer.Builder(this).build().apply {
        setMediaItem(MediaItem.fromUri(uri))
        repeatMode = Player.REPEAT_MODE_ONE // loop by default
        volume = 0f
        playWhenReady = true
        prepare()
        addListener(
          object : Player.Listener {
            override fun onRenderedFirstFrame() {
              mock.visibility = View.GONE
            }
          }
        )
      }
    demoPlayers.add(player)
    playerView.player = player
    playerView.setFullscreenButtonClickListener { expandDemo(playerView) }
  }

  /**
   * Grows the tapped preview into the fullscreen overlay via MaterialContainerTransform. The SAME
   * player is handed to the fullscreen view with [PlayerView.switchTargetView], so playback is
   * continuous with no re-buffer or reload.
   */
  private fun expandDemo(source: PlayerView) {
    val player = source.player ?: return
    expandedFrom = source
    demoExpandPlayer.setFullscreenButtonClickListener { collapseDemo() }

    beginDemoTransition(source, demoExpandRoot, EXPAND_MS)
    source.visibility = View.INVISIBLE
    demoExpandRoot.visibility = View.VISIBLE
    PlayerView.switchTargetView(player, source, demoExpandPlayer)
  }

  /** Shrinks the fullscreen overlay back into the preview it came from, handing the player back. */
  private fun collapseDemo() {
    val source = expandedFrom ?: return
    val player = demoExpandPlayer.player ?: return
    beginDemoTransition(demoExpandRoot, source, SHRINK_MS)
    demoExpandRoot.visibility = View.GONE
    source.visibility = View.VISIBLE
    PlayerView.switchTargetView(player, demoExpandPlayer, source)
    expandedFrom = null
  }

  /** Runs a Material container transform from [start] to [end], unless animations are disabled. */
  private fun beginDemoTransition(
    start: View,
    end: View,
    durationMs: Long
  ) {
    if (!animationsEnabled()) {
      return
    }
    val transform =
      MaterialContainerTransform().apply {
        startView = start
        endView = end
        addTarget(end)
        scrimColor = Color.TRANSPARENT
        duration = durationMs
        interpolator = emphasized
      }
    TransitionManager.beginDelayedTransition(rootFrame, transform)
  }

  /** Honors Settings > Developer options / accessibility "remove animations" (animator scale 0). */
  private fun animationsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

  private fun buildDots(count: Int) {
    dots.removeAllViews()
    val size = (8 * resources.displayMetrics.density).toInt()
    val margin = (4 * resources.displayMetrics.density).toInt()
    repeat(count) {
      val dot = View(this)
      dot.background = getDrawable(R.drawable.hero_circle)
      val lp =
        ViewGroup.MarginLayoutParams(size, size).apply {
          marginStart = margin
          marginEnd = margin
        }
      dots.addView(dot, lp)
    }
  }

  private fun updateDots(active: Int) {
    val on = themeColor(com.google.android.material.R.attr.colorPrimary)
    val off = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    for (i in 0 until dots.childCount) {
      dots
        .getChildAt(i)
        .background
        .mutate()
        .setColorFilter(
          if (i == active) {
            on
          } else {
            off
          },
          PorterDuff.Mode.SRC_IN
        )
    }
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

  // ---------- Main screen ----------

  private fun setupMainScreen() {
    accessCard = findViewById(R.id.accessCard)
    accessTitle = findViewById(R.id.accessTitle)
    accessBody = findViewById(R.id.accessBody)
    accessButton = findViewById(R.id.accessButton)
    accessButton.setOnClickListener { openNotificationSettings() }

    findViewById<android.widget.EditText>(R.id.serverInput).apply {
      setText(config.server)
      doAfterTextChanged { config.server = it?.toString().orEmpty() }
    }

    setupDemoVideo(mainContainer, R.id.mainDemoPlayer, R.id.mainDemoMock)
    setupCastCode()
    setupCastTvButton()
    buildCategoryRows()
  }

  private fun setupCastTvButton() {
    val button = findViewById<MaterialButton>(R.id.castTvButton)

    fun render() {
      button.text =
        if (config.castTvInstalled) {
          "Uninstall SponsorBlock TV"
        } else {
          "Reinstall SponsorBlock TV"
        }
    }
    render()
    button.setOnClickListener {
      if (config.castTvInstalled) {
        config.castTvInstalled = false
        config.screenId = null // forget the pairing so a reinstall is fresh
        CastReceiverService.stop(this)
      } else {
        config.castTvInstalled = true
        CastReceiverService.start(this)
      }
      render()
    }
  }

  private fun setupCastCode() {
    val codeView = findViewById<TextView>(R.id.castCode)
    val copyButton = findViewById<MaterialButton>(R.id.copyCodeButton)

    fun render(code: String?) {
      val spaced = code?.replace(Regex("(\\d{3})(?=\\d)"), "$1 ") ?: "- - - -"
      codeView.text = spaced
      copyButton.isEnabled = code != null
      fitCodeToWidth(codeView)
    }
    // Re-fit whenever the code's available width changes (rotation, different screen).
    codeView.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
      if (r - l != or - ol) {
        fitCodeToWidth(codeView)
      }
    }
    render(CastCapture.pairingCode)
    CastCapture.onPairingCode = { code -> runOnUiThread { render(code) } }
    copyButton.setOnClickListener {
      CastCapture.pairingCode?.let { code ->
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("TV code", code))
        android.widget.Toast
          .makeText(this, "Code copied", android.widget.Toast.LENGTH_SHORT)
          .show()
      }
    }
  }

  /**
   * Sizes the cast code to fill its available width on any screen. XML autosize misbehaves for a
   * single line inside a constrained column, so we measure and binary-search the largest fit.
   */
  private fun fitCodeToWidth(codeView: TextView) {
    codeView.post {
      val avail = codeView.width - codeView.paddingStart - codeView.paddingEnd
      val text = codeView.text?.toString().orEmpty()
      if (avail <= 0 || text.isEmpty()) {
        return@post
      }
      val paint = android.text.TextPaint(codeView.paint)
      val sd = resources.displayMetrics.scaledDensity
      var lo = 14f
      var hi = 60f
      repeat(12) {
        val mid = (lo + hi) / 2f
        paint.textSize = mid * sd
        if (paint.measureText(text) <= avail) {
          lo = mid
        } else {
          hi = mid
        }
      }
      codeView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, lo)
    }
  }

  private fun buildCategoryRows() {
    val container = findViewById<ViewGroup>(R.id.categoriesContainer)
    val inflater = LayoutInflater.from(this)
    Category.entries.forEach { cat ->
      val row = inflater.inflate(R.layout.row_category, container, false)
      row
        .findViewById<View>(R.id.colorDot)
        .background
        .mutate()
        .setColorFilter(cat.color.toInt(), PorterDuff.Mode.SRC_IN)
      row.findViewById<TextView>(R.id.categoryName).text = cat.displayName
      row.findViewById<TextView>(R.id.categoryDesc).text = cat.description
      row.findViewById<MaterialSwitch>(R.id.categorySwitch).apply {
        isChecked = config.mode(cat) == SkipMode.AUTO
        setOnCheckedChangeListener { _, checked ->
          config.setMode(
            cat,
            if (checked) {
              SkipMode.AUTO
            } else {
              SkipMode.DISABLED
            }
          )
        }
      }
      container.addView(row)
    }
  }

  private fun refreshAccessState() {
    if (hasAccess()) {
      accessCard.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorTertiaryContainer))
      accessTitle.setTextColor(themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer))
      accessBody.setTextColor(themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer))
      accessTitle.text = "You're all set"
      accessBody.text = "Enabled segments are skipped automatically while you watch."
      accessButton.text = "Manage access"
    } else {
      accessCard.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorPrimaryContainer))
      accessTitle.setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
      accessBody.setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
      accessTitle.text = "Access needed"
      accessBody.text =
        "Grant notification access so the app can watch the YouTube media session and skip for you."
      accessButton.text = "Grant access"
    }
  }

  // ---------- Helpers ----------

  private fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

  private fun openNotificationSettings() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

  private fun themeColor(attr: Int): Int {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
  }

  companion object {
    private const val EXPAND_MS = 300L
    private const val SHRINK_MS = 230L

    /** M3 "emphasized" easing. */
    private val emphasized = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
  }
}
