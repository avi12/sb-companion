package dev.sponsorblock.companion.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.sponsorblock.companion.Config
import dev.sponsorblock.companion.model.Category
import dev.sponsorblock.companion.model.SkipMode

/**
 * Minimal config UI, built programmatically to keep the scaffold dependency-light:
 *  - a button to grant Notification access (the one required permission)
 *  - per-category auto-skip switches
 *  - server override + a debug "manual videoID" field to verify the skip engine end-to-end
 *
 * The next agent can replace this with a proper Material/Compose screen and a now-playing card.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: Config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(header("SponsorBlock Companion"))

        root.addView(Button(this).apply {
            text = "Grant notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })

        root.addView(label("Server"))
        root.addView(EditText(this).apply {
            setText(config.server)
            setOnFocusChangeListener { _, focused -> if (!focused) config.server = text.toString() }
        })

        root.addView(label("Debug: manual videoID (11 chars) — forces the skip engine to use this ID"))
        root.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(config.manualVideoId.orEmpty())
            setOnFocusChangeListener { _, focused ->
                if (!focused) config.manualVideoId = text.toString().takeIf { it.length == 11 }
            }
        })

        root.addView(label("Auto-skip categories"))
        Category.entries.forEach { cat ->
            root.addView(Switch(this).apply {
                text = cat.id
                isChecked = config.mode(cat) == SkipMode.AUTO
                setOnCheckedChangeListener { _, checked ->
                    config.setMode(cat, if (checked) SkipMode.AUTO else SkipMode.DISABLED)
                }
            })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setPadding(0, 0, 0, 24)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setPadding(0, 32, 0, 8)
    }
}
