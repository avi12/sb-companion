// Top-level build file. Versions kept current as of mid-2026; the next agent should run
// `./gradlew assembleDebug` and bump any that have drifted.
plugins {
  id("com.android.application") version "8.5.2" apply false
  id("org.jetbrains.kotlin.android") version "1.9.24" apply false
  // Kotlin lint: ktlint = formatting (mirrors the eslint/stylistic half),
  // detekt = correctness/bug rules (mirrors the oxlint half). Rule intent is
  // ported from ../youtube-time-manager (.oxlintrc.json + eslint.config.js).
  id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
  id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

// Convenience aggregates, mirroring the JS repo's `pnpm dev:lint` / auto-fix.
// Named to avoid the Android plugin's built-in `lint` task.
//   ./gradlew ktLint    -> check formatting (ktlint) + correctness (detekt)
//   ./gradlew ktLintFix -> auto-fix formatting, then re-check correctness
tasks.register("ktLint") {
  group = "verification"
  description = "Run ktlint + detekt (Kotlin lint)."
  dependsOn(":app:ktlintCheck", ":app:detekt")
}
tasks.register("ktLintFix") {
  group = "verification"
  description = "Auto-fix ktlint formatting, then run detekt."
  dependsOn(":app:ktlintFormat", ":app:detekt")
}
