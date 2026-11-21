plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val signStore: String? = System.getenv("SIGN_STORE")

android {
    namespace = "tw.simpletech.tvapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.simpletech.tvapp"
        minSdk = 21
        targetSdk = 34
        versionCode = run
        versionName = "4.2"
    }

    signingConfigs {
        create("release") {
            if (signStore != null) {
                storeFile = file(signStore)
                storePassword = System.getenv("SIGN_PASS")
                keyAlias = "tvapp"
                keyPassword = System.getenv("SIGN_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signStore != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
}
