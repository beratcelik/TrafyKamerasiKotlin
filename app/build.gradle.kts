import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

// Native vision backend is opt-in: only engage CMake when setup-ncnn.sh has populated
// the prebuilt headers + libs. Collaborators who haven't run the script still get a
// clean build; the app surfaces a "vision backend not installed" state at runtime.
val ncnnPrebuiltDir = file("src/main/cpp/ncnn")
val ncnnPrebuiltAvailable = ncnnPrebuiltDir.resolve("arm64-v8a/lib/libncnn.a").exists()

android {
    namespace = "com.example.trafykamerasikotlin"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.trafykamerasikotlin"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("boolean", "NCNN_PREBUILT_BUNDLED", ncnnPrebuiltAvailable.toString())
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile     = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias      = keystoreProperties["keyAlias"] as String
                keyPassword   = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        localeFilters += listOf("en", "tr")
        // NCNN loads .param + .bin via mmap; compressing them blocks mmap and slows load.
        noCompress += listOf("param", "bin", "labels")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Only engage the C++ build when the prebuilt NCNN blobs are present
    // (populated by scripts/setup-ncnn.sh). Keeps the repo clone → build path
    // green for anyone who hasn't fetched binaries yet.
    if (ncnnPrebuiltAvailable) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

// Verify every packaged .so has 16 KB-aligned LOAD segments. Google Play
// requires this on Android 15+ (Nov 2025 deadline, with a May 31 2026 extension).
// Fails the build loudly if a prebuilt dependency regresses.
val verify16KbAlignment = tasks.register<Exec>("verify16KbAlignment") {
    description = "Verifies all packaged native libs have 16 KB-aligned LOAD segments (Google Play requirement)"
    group = "verification"
    val script = rootProject.file("scripts/verify-16kb-alignment.sh")
    val mergedLibs = layout.buildDirectory.dir("intermediates/merged_native_libs")
    onlyIf { script.exists() && mergedLibs.get().asFile.exists() }
    commandLine("bash", script.absolutePath, mergedLibs.get().asFile.absolutePath)
}

// Wire the check into debug and release assembly so it runs whenever native libs
// are produced. Still a no-op for builds without prebuilts.
afterEvaluate {
    listOf("assembleDebug", "assembleRelease").forEach { taskName ->
        tasks.findByName(taskName)?.finalizedBy(verify16KbAlignment)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.ijkplayer)
    implementation(libs.tink.android)
    // ONNX Runtime for the plate-OCR model (CCT-S transformer). Kept separate
    // from the NCNN stack because the CCT's attention ops don't convert
    // cleanly to NCNN on this version (same reason YOLO26 CPU backend breaks).
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}