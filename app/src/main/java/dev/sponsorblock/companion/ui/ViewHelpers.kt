package dev.sponsorblock.companion.ui

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import androidx.core.app.NotificationManagerCompat

/** Resolves a theme color attribute to its ARGB int for the current theme. */
fun Context.themeColor(attr: Int): Int {
  val tv = TypedValue()
  theme.resolveAttribute(attr, tv, true)
  return tv.data
}

/** Tints a view's (mutated) background with a solid SRC_IN color filter. */
fun View.tintBackground(color: Int) {
  background.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
}

/** Honors Settings > Developer options / accessibility "remove animations" (animator scale 0). */
fun Context.animationsEnabled(): Boolean =
  Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** True while our notification-listener service is enabled in system settings. */
fun Context.hasNotificationAccess(): Boolean =
  NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

/** Opens the system notification-access settings. */
fun Context.openNotificationSettings() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

/** Opens the system accessibility settings. */
fun Context.openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

/** True once the user has enabled our seekbar-overlay accessibility service in system settings. */
fun Context.hasSeekbarAccessibility(): Boolean {
  val enabled =
    Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
      ?: return false
  val component = "$packageName/.overlay.SeekbarAccessibilityService"
  val flat = "$packageName/$packageName.overlay.SeekbarAccessibilityService"
  return enabled.split(':').any { it == component || it == flat }
}
