import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Konfigurasi signing rilis.
// Urutan pencarian: -PKEYSTORE_PATH=... / env KEYSTORE_PATH / keystore.properties
// Jangan pernah commit file keystore atau keystore.properties ke repositori.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingValue(name: String): String? {
    val fromGradle = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(name)?.takeIf { it.isNotBlank() }
    val fromFile = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
    return fromGradle ?: fromEnv ?: fromFile
}

val releaseKeystorePath = signingValue("KEYSTORE_PATH")
val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

android {
    namespace = "com.pangdash.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pangdash.launcher"
        minSdk = 24
        targetSdk = 32
        // CI mengirim versionCodeOverride berbasis nomor build agar OTA selalu naik.
        versionCode = (findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 101
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseKeystorePath as String)
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // APK debug-signing berubah setiap runner CI, sehingga update in-place gagal.
            // Jika keystore rilis belum tersedia (build lokal), pakai debug signing agar tetap bisa diuji.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
}
