import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test-only module (the one allowed extra module): Macrobenchmarks and the Baseline Profile
// generator for :app. They need a phone signed into the seeded store (ticket 62); CI only builds
// them. Precondition for every benchmark: the benchmark build of :app is installed, signed in,
// approved and synced; each benchmark fails with a clear message otherwise.
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.baselineprofile)
}

android {
  namespace = "com.sadad.benchmark"
  compileSdk {
    version = release(37)
  }
  defaultConfig {
    minSdk = 28
    targetSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  targetProjectPath = ":app"
  buildTypes {
    create("benchmark") {
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }

baselineProfile { useConnectedDevices = true }

dependencies {
  implementation(libs.ext.junit)
  implementation(libs.uiautomator)
  implementation(libs.benchmark.macro.junit4)
}

androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
