import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

// -- speech model -------------------------------------------------------------------------------
//
// Push-to-talk transcribes on the phone with NVIDIA's Parakeet TDT 110M (int8) through
// sherpa-onnx — the same model and the same code path BrightThumb ships for voice typing,
// chosen there for being both more accurate and about 2.5x faster than whisper tiny.en on
// short utterances. The archive is ~120MB and is not source, so it is fetched into the build
// directory once per machine and unpacked into assets; both are gitignored. A clean checkout
// builds with no extra step. Same task shape as BrightThumb's, so a fix there applies here.

val speechModelUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2"

val speechModelArchive = layout.buildDirectory.file("speech-model/parakeet-110m-int8.tar.bz2")
val speechModelAssets = layout.projectDirectory.dir("src/main/assets/parakeet-110m-en")

val downloadSpeechModel by tasks.registering {
    description = "Downloads the Parakeet TDT 110M int8 model archive."
    val target = speechModelArchive
    val url = speechModelUrl
    outputs.file(target)
    outputs.upToDateWhen { target.get().asFile.length() > 100_000_000L }
    doLast {
        val out = target.get().asFile
        if (out.length() > 100_000_000L) return@doLast
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        URI(url).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        // Renamed only once complete, so an interrupted download is never mistaken for a
        // finished one on the next build.
        tmp.renameTo(out)
    }
}

val unpackSpeechModel by tasks.registering(Sync::class) {
    description = "Unpacks the speech model into assets."
    dependsOn(downloadSpeechModel)
    from({ tarTree(resources.bzip2(speechModelArchive)) }) {
        include("**/encoder.int8.onnx", "**/decoder.int8.onnx", "**/joiner.int8.onnx", "**/tokens.txt")
        // Flattened: the archive nests everything under its own directory name, and
        // voice/Listener.kt addresses the files directly.
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(speechModelAssets)
}

tasks.named("preBuild") { dependsOn(unpackSpeechModel) }

// -- signing ------------------------------------------------------------------------------------
//
// The release key is a CI secret: build.yml decodes KEYSTORE_B64 to keystore/brighthermes.jks
// (gitignored) and KEYSTORE_PASSWORD opens it. Same arrangement as BrightControl. A build
// without the secret still works and still produces an installable APK — signed with the debug
// key, so it will not install over a release, and the fingerprint check in build.yml refuses
// to publish it. That is the right failure.
val keystoreFile = rootProject.file("keystore/brighthermes.jks")
val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
val canSignRelease = keystoreFile.exists() && keystorePassword.isNotEmpty()

android {
    namespace = "com.gios.brighthermes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gios.brighthermes"
        minSdk = 34 // Light Phone III runs Android 14 — the only target device.
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "0.4.0"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only, and the sherpa-onnx AAR in libs/ is stripped to arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "brighthermes"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            // Sign debug with the release key too when it is available, so a local
            // `adb install -r` replaces the installed release instead of failing on a
            // certificate mismatch.
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                if (System.getenv("CI") != null) {
                    throw GradleException(
                        "keystore/brighthermes.jks is missing or KEYSTORE_PASSWORD is empty: " +
                            "refusing to publish an APK signed with a throwaway key. " +
                            "See .github/workflows/build.yml.",
                    )
                }
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        // The model files are already compressed; zipping them again costs build time and
        // makes the first launch slower to page them in.
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    // Shake-to-report, the wheel, the greys, Akkurat, the camera-button keycodes.
    implementation("com.gios:light-common:1.8.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The one network client. WebSocket for chat, plain HTTP for the deck. No Google
    // dependency, matching the rest of the Bright* apps.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-device speech-to-text: NVIDIA Parakeet TDT 110M through sherpa-onnx, prebuilt AAR
    // from https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.6, arm64 only.
    implementation(files("libs/sherpa-onnx-1.13.6-arm64.aar"))

    // Pure-Kotlin logic (deck JSON, protocol frames, tile staleness) is kept free of Android
    // imports so it can be tested here on the JVM. `org.json` is the platform's at runtime
    // and a stub in unit tests, so a real one rides on the test classpath only.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
