import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
}

// Ensure debug.keystore exists before signing config evaluation (especially on CI / GitHub Actions)
val debugKeystoreFile = file("${rootDir}/debug.keystore")
if (!debugKeystoreFile.exists() || debugKeystoreFile.length() == 0L) {
  val base64File = file("${rootDir}/debug.keystore.base64")
  if (base64File.exists() && base64File.length() > 0L) {
    try {
      val decoded = Base64.getDecoder().decode(base64File.readText().trim())
      debugKeystoreFile.writeBytes(decoded)
    } catch (_: Throwable) {}
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.applet.lidyvg"
    minSdk = 28
    targetSdk = 36
    versionCode = 2
    versionName = "2.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = false
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.media)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.ui)
  implementation(libs.media3.datasource)
  implementation(libs.media3.extractor)
  implementation(libs.media3.ffmpeg.decoder)
  implementation(libs.media3.session)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.kotlinx.coroutines.test)
}
