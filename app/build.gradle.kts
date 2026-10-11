plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = System.getenv("SADAD_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("SADAD_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("SADAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SADAD_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.sadad.app"
    buildFeatures { buildConfig = true }
    // Live backend: the Supabase project's sadad-api edge function.
    // A legacy self-hosted server ending in /api still works for debug builds.
    val apiBaseUrl = providers.gradleProperty("SADAD_API_BASE_URL")
        .getOrElse("https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api")
    // Publishable key only. The service-role key must never reach the app.
    val supabasePublishableKey = providers.gradleProperty("SADAD_SUPABASE_PUBLISHABLE_KEY")
        .getOrElse("sb_publishable_dK7HuYY8TbO8ZiBPbOZ2cw_1ZRL_chh")
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        applicationId = providers.gradleProperty("SADAD_TEST_APPLICATION_ID").getOrElse("com.sadad.app.test")
        testInstrumentationRunner = providers.gradleProperty("SADAD_TEST_RUNNER").getOrElse("com.sadad.app.QaInstrumentation")
        minSdk = 24
        targetSdk = 34
        versionCode = 36
        versionName = providers.gradleProperty("SADAD_VERSION_NAME").getOrElse("2.5.10")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${supabasePublishableKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        manifestPlaceholders["allowCleartextTraffic"] = "false"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("server") {
            dimension = "edition"
            applicationId = providers.gradleProperty("SADAD_APPLICATION_ID").getOrElse("com.sadad.app")
            buildConfigField("boolean", "STANDALONE_MODE", "false")
        }
        create("standalone") {
            dimension = "edition"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            resValue("string", "app_name", "سدد - مستقل")
            buildConfigField("boolean", "STANDALONE_MODE", "true")
        }
    }
    signingConfigs {
        if (releaseSigningReady) create("release") {
            storeFile = file(releaseStoreFile!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        debug {
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["allowCleartextTraffic"] = "false"
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies { }


