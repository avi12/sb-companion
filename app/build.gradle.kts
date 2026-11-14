import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jlleitschuh.gradle.ktlint")
  id("io.gitlab.arturbosch.detekt")
}

// Release signing creds live in local.properties (git-ignored), never in VCS.
val localProps =
  Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
  }
val hasReleaseSigning = localProps.getProperty("RELEASE_STORE_FILE") != null

android {
  namespace = "dev.sponsorblock.companion"
  compileSdk = 34

  defaultConfig {
    applicationId = "dev.sponsorblock.companion"
    minSdk = 29 // Android 10 — MediaSession + notification-listener are stable here
    targetSdk = 34
    versionCode = 2
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = rootProject.file(localProps.getProperty("RELEASE_STORE_FILE"))
        storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
        keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
        keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.viewpager2:viewpager2:1.1.0")
  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-ui:1.4.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.6.1")
  androidTestImplementation("androidx.test:rules:1.6.1")
}

// ktlint carries the formatting rules (2-space indent, 120 col, final newline,
// no wildcard imports, sorted imports) — configured via the repo .editorconfig.
ktlint {
  version.set("1.3.1")
  android.set(true)
  ignoreFailures.set(false)
  reporters {
    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
  }
}

// detekt carries the correctness rules (val-over-var, unused code, potential bugs)
// — the oxlint half. Rules come from the shared preset.
detekt {
  buildUponDefaultConfig = true
  config.setFrom(files("$rootDir/node_modules/kotlin-config-avi12/detekt.yml").filter { it.exists() })
  autoCorrect = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
  jvmTarget = "17"
}
