package dev.sponsorblock.companion.ui

import android.text.TextPaint
import android.util.TypedValue
import android.widget.TextView

/** Formatting and auto-fit for the TV pairing code shown on the main screen. */
object CastCodeFormatter {
  /** Groups the digits (e.g. "12345678" -> "123 456 78"), or a placeholder when absent. */
  fun format(code: String?): String =
    code?.replace(Regex("(\\d{3})(?=\\d)"), "$1 ") ?: "- - - -"

  /**
   * Sizes the cast code to fill its available width on any screen. XML autosize misbehaves for a
   * single line inside a constrained column, so we measure and binary-search the largest fit.
   */
  fun fitToWidth(codeView: TextView) {
    codeView.post {
      val avail = codeView.width - codeView.paddingStart - codeView.paddingEnd
      val text = codeView.text?.toString().orEmpty()
      if (avail <= 0 || text.isEmpty()) {
        return@post
      }
      val paint = TextPaint(codeView.paint)
      val sd = codeView.resources.displayMetrics.scaledDensity
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
      codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, lo)
    }
  }
}
