import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from a file OUTSIDE the repo (default ~/.fintap/keystore.properties,
// override with FINTAP_KEYSTORE_PROPS). If it's missing, the release APK is built unsigned —
// never silently signed with the debug key. F-Droid-style builders sign it themselves.
val keystoreProps: Properties? = run {
    val path = System.getenv("FINTAP_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/.fintap/keystore.properties"
    val f = file(path)
    if (f.exists()) Properties().apply { f.inputStream().use { load(it) } } else null
}

android {
    namespace = "com.fintap"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fastdaima.fintap"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release is what goes on the phone: R8 + non-debuggable = fastest startup.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (keystoreProps != null) signingConfigs.getByName("release") else null
        }
    }

    // Google's encrypted dependency blob in the signing block: opaque to users, flagged by IzzyOnDroid/F-Droid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Only dependency: the side drawer. Everything else is platform.
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    testImplementation("junit:junit:4.13.2")
}
