import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.baselineprofile)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
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

// The pinned offline-lease key: the public half of the api's SADAD_LEASE_PRIVATE_JWK secret (ticket
// 27). An empty key means no offline write is allowed (ticket 47).
val leaseKeyX =
    providers
        .gradleProperty("SADAD_LEASE_KEY_X")
        .getOrElse("w1NJG8ul9K-a0cPomIp5m0qPFBlQXoWTfd6504pGzIY")
val leaseKeyY =
    providers
        .gradleProperty("SADAD_LEASE_KEY_Y")
        .getOrElse("g9oiLjzP9j6aZMAk_X_Q45PKvEc5vYPRAfsfbFFdvSk")

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
    targetSdk = 36
    versionCode = 40
    versionName = providers.gradleProperty("SADAD_VERSION_NAME").getOrElse("3.0.0")
    buildConfigField("String", "SUPABASE_URL", quoted(supabaseUrl))
    buildConfigField("String", "API_BASE_URL", quoted("$supabaseUrl/functions/v1/api"))
    buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(supabasePublishableKey))
    buildConfigField("String", "LEASE_PUBLIC_KEY_X", quoted(leaseKeyX))
    buildConfigField("String", "LEASE_PUBLIC_KEY_Y", quoted(leaseKeyY))
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
    // What the :benchmark module measures: the release build (R8 on) signed with the debug key so a
    // phone installs it without the production key. Not debuggable, or the timings are wrong.
    create("benchmark") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      isDebuggable = false
      matchingFallbacks += "release"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Exported Room schemas are committed once ticket 42 adds the database.
room { schemaDirectory("$projectDir/schemas") }

// The unit tests read the shared contract fixtures straight from the repository.
val contract = rootProject.layout.projectDirectory.dir("contract")

tasks.withType<Test>().configureEach {
  inputs.dir(contract).withPropertyName("contract").withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("sadad.fixtures", contract.dir("fixtures").asFile.absolutePath)
  // targetSdk 36 runs Robolectric's Android 16 sandbox. Its ApplicationSharedMemory setup reaches
  // FileDescriptor internals through reflection on jdk.internal.access, which JDK 21 keeps closed
  // (stack trace: FileDescriptorInterceptor.setInt, "Failed to interact with raw FileDescriptor
  // internals"). This is the one opening Robolectric's own build gives its tests for that.
  jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

// `contract/errors.json` is the one source; the app reads it as the `errors.json` asset.
val generateErrorsAsset =
    tasks.register<ErrorsAssetTask>("generateErrorsAsset") {
      source.set(contract.file("errors.json"))
      outputDirectory.set(layout.buildDirectory.dir("generated/errorsAsset"))
    }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        generateErrorsAsset,
        ErrorsAssetTask::outputDirectory,
    )
  }
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
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  implementation(libs.core.ktx)
  implementation(libs.biometric)
  implementation(libs.datastore.preferences)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.navigation.compose)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  implementation(libs.work.runtime.ktx)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.profileinstaller)
  implementation(project(":ledger-core"))
  baselineProfile(project(":benchmark"))

  ksp(libs.room.compiler)

  debugImplementation(libs.compose.ui.tooling)
  debugImplementation(libs.compose.ui.test.manifest)

  testImplementation(libs.junit)
  testImplementation(libs.ext.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.turbine)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver3)
  testImplementation(libs.work.testing)
  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui.test.junit4)

  androidTestImplementation(libs.ext.junit)
  androidTestImplementation(libs.test.runner)
  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.compose.ui.test.junit4)
}

abstract class ErrorsAssetTask : DefaultTask() {
  @get:InputFile abstract val source: RegularFileProperty

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun copyAsset() {
    val target = outputDirectory.get().asFile.resolve(source.get().asFile.name)
    target.parentFile.mkdirs()
    source.get().asFile.copyTo(target, overwrite = true)
  }
}
