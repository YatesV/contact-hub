plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yatesv.contacthub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yatesv.contacthub"
        minSdk = 29
        targetSdk = 35
        // CI sets VERSION_CODE so every build installs over the last one
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("VERSION_CODE") ?: "0")
        // Only the S24's chip type: keeps the on-device AI libraries from quadrupling the APK size
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release key: private, stored in GitHub secrets (ORBIT_KEYSTORE_B64 / ORBIT_KEYSTORE_PASSWORD) and
    // written to a temp file by CI. The old key was public in this repo, so anyone could have signed a
    // fake "update"; with a private key only these builds install over Orbit.
    // Without the secrets (local builds) it falls back to the old shared key.
    val releaseStore = System.getenv("ORBIT_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        create("legacy") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("ORBIT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ORBIT_KEY_ALIAS") ?: "orbit"
            keyPassword = System.getenv("ORBIT_KEY_PASSWORD") ?: System.getenv("ORBIT_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("legacy")
        }
        // The build you install: optimized (R8) and not debuggable, so animations run much smoother.
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("legacy")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Shrinks videos to fit picture-message (MMS) size limits
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // QR code for "My contact" (encoder only)
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // On-device smart replies, language detection and translation (no account or key needed)
    implementation("com.google.mlkit:smart-reply:17.0.4")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
