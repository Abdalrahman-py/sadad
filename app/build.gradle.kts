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
    // Origins are build-time settings; no account preference can redirect credentials.
    val apiBaseUrl = providers.gradleProperty("SADAD_API_BASE_URL")
        .getOrElse("https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api")
    // The gateway that issues the Auth envelope the signed ledger requires. Login and token refresh
    // go here; every other legacy route still goes to apiBaseUrl. Both are pinned into the build so
    // a released app cannot be pointed at another origin.
    val authBaseUrl = providers.gradleProperty("SADAD_AUTH_BASE_URL")
        .getOrElse("https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-auth")
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
        versionCode = 38
        versionName = providers.gradleProperty("SADAD_VERSION_NAME").getOrElse("2.5.12")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "AUTH_BASE_URL", "\"${authBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${supabasePublishableKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        manifestPlaceholders["allowCleartextTraffic"] = "false"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("server") {
            dimension = "edition"
            applicationId = providers.gradleProperty("SADAD_APPLICATION_ID").getOrElse("com.sadad.app")
            if (applicationId == "com.sadad.qa") resValue("string", "app_name", "سدد - فحص الجهاز")
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

dependencies {
    // JVM tests only. The signed ledger is pure Java over org.json, and Android's unit-test runtime stubs
    // org.json, so the real implementation has to be on the test classpath for it to run at all.
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.withType<Test>().configureEach {
    // The live harness talks to the deployed backend, but only when told where the store login is:
    // no path, no run. The password itself never enters the repository or the build file.
    systemProperty("sadad.live.credentials", providers.systemProperty("sadad.live.credentials").getOrElse(""))
    testLogging { showStandardStreams = true }
}
