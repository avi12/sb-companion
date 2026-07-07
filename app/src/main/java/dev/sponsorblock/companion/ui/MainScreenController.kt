package dev.sponsorblock.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.R
import dev.sponsorblock.companion.cast.CastCapture
import dev.sponsorblock.companion.cast.CastReceiverService
import dev.sponsorblock.companion.model.Category
import dev.sponsorblock.companion.model.SkipMode

/**
 * Wires the main settings screen: auto-skip categories, the notification-access card, the seekbar
 * overlay card, the server field, and the cast (TV pairing) section. [onResume] refreshes the two
 * state-dependent cards.
 */
class MainScreenController(
  private val activity: AppCompatActivity,
  private val config: Config,
  private val mainContainer: View,
  private val demo: DemoPlayerController
) {
  private lateinit var accessCard: MaterialCardView
  private lateinit var accessTitle: TextView
  private lateinit var accessBody: TextView
  private lateinit var accessButton: MaterialButton

  private lateinit var overlaySwitch: MaterialSwitch
  private lateinit var overlayBody: TextView
  private lateinit var overlayButton: MaterialButton

  fun setup() {
    setupAccessCard()
    setupServerInput()
    demo.bind(mainContainer, R.id.mainDemoPlayer, R.id.mainDemoMock)
    setupCastCode()
    setupCastTvButton()
    setupOverlayCard()
    buildCategoryRows()
  }

  fun onResume() {
    refreshAccessState()
    refreshOverlayState()
  }

  private fun setupAccessCard() {
    accessCard = activity.findViewById(R.id.accessCard)
    accessTitle = activity.findViewById(R.id.accessTitle)
    accessBody = activity.findViewById(R.id.accessBody)
    accessButton = activity.findViewById(R.id.accessButton)
    accessButton.setOnClickListener { activity.openNotificationSettings() }
  }

  private fun setupServerInput() {
    activity.findViewById<EditText>(R.id.serverInput).apply {
      setText(config.server)
      doAfterTextChanged { config.server = it?.toString().orEmpty() }
    }
  }

  private fun setupOverlayCard() {
    overlaySwitch = activity.findViewById(R.id.overlaySwitch)
    overlayBody = activity.findViewById(R.id.overlayBody)
    overlayButton = activity.findViewById(R.id.overlayButton)
    overlaySwitch.isChecked = config.seekbarOverlayEnabled
    overlaySwitch.setOnCheckedChangeListener { _, checked ->
      config.seekbarOverlayEnabled = checked
    }
    overlayButton.setOnClickListener { activity.openAccessibilitySettings() }
  }

  private fun refreshOverlayState() {
    val on = activity.hasSeekbarAccessibility()
    overlayButton.text =
      if (on) {
        "Manage accessibility"
      } else {
        "Enable accessibility"
      }
    overlayBody.text =
      if (on) {
        "Colored SponsorBlock bars are painted on the YouTube seekbar as you watch."
      } else {
        "Turn on the “SponsorBlock seekbar overlay” accessibility service to paint segment bars on YouTube’s seekbar."
      }
  }

  private fun setupCastTvButton() {
    val button = activity.findViewById<MaterialButton>(R.id.castTvButton)

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
        CastReceiverService.stop(activity)
      } else {
        config.castTvInstalled = true
        CastReceiverService.start(activity)
      }
      render()
    }
  }

  private fun setupCastCode() {
    val codeView = activity.findViewById<TextView>(R.id.castCode)
    val copyButton = activity.findViewById<MaterialButton>(R.id.copyCodeButton)

    fun render(code: String?) {
      codeView.text = CastCodeFormatter.format(code)
      copyButton.isEnabled = code != null
      CastCodeFormatter.fitToWidth(codeView)
    }
    // Re-fit whenever the code's available width changes (rotation, different screen).
    codeView.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
      if (r - l != or - ol) {
        CastCodeFormatter.fitToWidth(codeView)
      }
    }
    render(CastCapture.pairingCode)
    CastCapture.onPairingCode = { code -> activity.runOnUiThread { render(code) } }
    copyButton.setOnClickListener {
      CastCapture.pairingCode?.let { code ->
        val clip = activity.getSystemService(ClipboardManager::class.java)
        clip.setPrimaryClip(ClipData.newPlainText("TV code", code))
        Toast.makeText(activity, "Code copied", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun buildCategoryRows() {
    val container = activity.findViewById<ViewGroup>(R.id.categoriesContainer)
    val inflater = LayoutInflater.from(activity)
    Category.entries.forEach { cat ->
      val row = inflater.inflate(R.layout.row_category, container, false)
      row.findViewById<View>(R.id.colorDot).tintBackground(cat.color.toInt())
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
    if (activity.hasNotificationAccess()) {
      accessCard.setCardBackgroundColor(activity.themeColor(com.google.android.material.R.attr.colorTertiaryContainer))
      accessTitle.setTextColor(activity.themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer))
      accessBody.setTextColor(activity.themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer))
      accessTitle.text = "You're all set"
      accessBody.text = "Enabled segments are skipped automatically while you watch."
      accessButton.text = "Manage access"
    } else {
      accessCard.setCardBackgroundColor(activity.themeColor(com.google.android.material.R.attr.colorPrimaryContainer))
      accessTitle.setTextColor(activity.themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
      accessBody.setTextColor(activity.themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
      accessTitle.text = "Access needed"
      accessBody.text =
        "Grant notification access so the app can watch the YouTube media session and skip for you."
      accessButton.text = "Grant access"
    }
  }
}
