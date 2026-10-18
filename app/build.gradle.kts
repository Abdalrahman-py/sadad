import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val releaseStoreFile = System.getenv("SADAD_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("SADAD_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("SADAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SADAD_KEY_PASSWORD")
val releaseSigningReady =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all {
      !it.isNullOrBlank()
    }

fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
  namespace = "com.sadad.app"
  buildFeatures {
    buildConfig = true
    compose = true
  }
  val supabaseUrl =
      providers
          .gradleProperty("SADAD_SUPABASE_URL")
          .getOrElse("https://vhftjmiltqfwmrszdtgf.supabase.co")
          .trimEnd('/')
  // Publishable key only. The service-role key must never reach the app.
  val supabasePublishableKey =
      providers
          .gradleProperty("SADAD_SUPABASE_PUBLISHABLE_KEY")
          .getOrElse("sb_publishable_dK7HuYY8TbO8ZiBPbOZ2cw_1ZRL_chh")
  compileSdk {
    version = release(37)
  }
  defaultConfig {
    applicationId = "com.sadad.app"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    minSdk = 26
    targetSdk = 34
    versionCode = 40
    versionName = providers.gradleProperty("SADAD_VERSION_NAME").getOrElse("3.0.0")
    buildConfigField("String", "SUPABASE_URL", quoted(supabaseUrl))
    buildConfigField("String", "API_BASE_URL", quoted("$supabaseUrl/functions/v1/api"))
    buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(supabasePublishableKey))
    manifestPlaceholders["allowCleartextTraffic"] = "false"
  }
  signingConfigs {
    if (releaseSigningReady)
        create("release") {
          storeFile = file(releaseStoreFile!!)
          storePassword = releaseStorePassword
          keyAlias = releaseKeyAlias
          keyPassword = releaseKeyPassword
        }
  }
  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
    }
    release {
      manifestPlaceholders["allowCleartextTraffic"] = "false"
      isMinifyEnabled = true
      isShrinkResources = true
      if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

androidComponents {
  // The release workflow also verifies the minified variant's BuildConfig.
  beforeVariants(selector().withBuildType("release")) {
    it.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.activity.compose)
  implementation(project(":ledger-core"))
  testImplementation(libs.junit)
}
