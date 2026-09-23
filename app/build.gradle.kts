import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.google.services) apply false
}

// Firebase Cloud Messaging is opt-in on the drop-in `google-services.json` (Firebase console →
// Project settings → your Android app → download). Applied only when that file is present so a
// checkout without it (fresh clone / CI without secrets) still builds — FCM is simply inactive
// until the file is added. Same conditional-local-file pattern as keystore.properties below.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.64.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc") {
                    option("lite")
                }
                create("grpckt") {
                    option("lite")
                }
            }
            it.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

ksp {
    // Room schema history — lets Migration tests validate a hand-written migration's resulting
    // schema against what Room actually expects, instead of hoping the SQL matches by hand.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Release signing — credentials live in a gitignored keystore.properties (see .gitignore),
// never in this file. Loaded conditionally so a checkout without that file (a fresh clone, or
// CI without secrets configured) can still build debug/unsigned-release variants instead of
// failing the whole Gradle configuration phase outright.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Third-party API keys — same gitignored-local-file pattern as keystore.properties above, but in
// local.properties (already gitignored for the SDK path) rather than a dedicated file, since this
// is a single dev-convenience key rather than a release-signing secret.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.kachat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kachat.app"
        minSdk = 26
        targetSdk = 36
        // ---- Version train ----
        // 4.1 shipped on 2026-09-15; every build from here is 5.0, the same train iOS runs.
        //
        // KACHAT_BUILD_NUMBER is the number people see: the About row and the crash and
        // diagnostics reports read "5.0 (1)", "5.0 (2)", ... through the betas, and plain "5.0"
        // once KACHAT_IS_RELEASE is flipped for the store build. Bump it for every build that
        // is handed out (the GitHub APK, a Play beta) - the counterpart of iOS's
        // Version.xcconfig KACHAT_BUILD_NUMBER, so the two apps report the same shape.
        //
        // versionCode is a separate thing and only ever goes up: Play's high-water mark is
        // permanent and per app, and unlike iOS it does NOT reset when versionName changes
        // (4.1's builds ran 35..47). A device will not treat a rebuild as an update unless it
        // moves, so it goes up with every handed-out build too, alongside the build number.
        val kachatBuildNumber = 12
        val kachatIsRelease = false
        versionCode = 81
        versionName = "5.1"
        buildConfigField("int", "KACHAT_BUILD_NUMBER", kachatBuildNumber.toString())
        buildConfigField("boolean", "KACHAT_IS_RELEASE", kachatIsRelease.toString())

        buildConfigField(
            "String",
            "CHANGENOW_API_KEY",
            "\"${localProperties.getProperty("changenow.api.key", "")}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // The Play upload key, which is NOT the key the GitHub APK is signed with: Play
            // knows the app by the certificate of its first upload, and an APK already installed
            // from GitHub can only be updated by something signed the same way as itself. So the
            // play flavour's release build is signed with this one (see androidComponents below)
            // and everything else keeps the key above. Optional: a checkout without it still
            // builds the GitHub APK.
            if (keystoreProperties.getProperty("uploadStoreFile") != null) {
                create("upload") {
                    storeFile = rootProject.file(keystoreProperties.getProperty("uploadStoreFile"))
                    storePassword = keystoreProperties.getProperty("uploadStorePassword")
                    keyAlias = keystoreProperties.getProperty("uploadKeyAlias")
                    keyPassword = keystoreProperties.getProperty("uploadKeyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    // Where a build is handed out from. Same package, same signing key, same code - the one
    // thing that differs is the launcher name: the GitHub APK calls itself "KaChat APK" (see
    // src/github/res) so a person can tell it from the Play Store's "KaChat" at a glance.
    // Installing one over the other still updates in place, since Android sees one package.
    // Play builds: bundlePlayRelease. GitHub APK: assembleGithubRelease.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("github") {
            dimension = "distribution"
            // The handed-out APK carries arm64 alone: every phone sold for years is 64-bit ARM,
            // while x86/x86_64 are emulators and Chromebooks and armeabi-v7a is 32-bit hardware
            // long out of production - and WebRTC alone ships a 12MB library per architecture,
            // which made the APK 57MB, most of it code the people downloading it can never run.
            // The Play bundle keeps all four, since Play serves each device only its own, so a
            // 32-bit or x86 device still installs from there.
            ndk { abiFilters += listOf("arm64-v8a") }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Required for gRPC
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}


// Only the Play release is signed with the upload key; the GitHub APK and every debug build
// keep their own. A build type's signing config wins over a flavour's, so the swap is made per
// variant rather than on the play flavour.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "play").withBuildType("release")) { variant ->
        android.signingConfigs.findByName("upload")?.let { variant.signingConfig.setConfig(it) }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    // Per-app language switching (AppCompatDelegate.setApplicationLocales) - the modern,
    // Google-recommended API, native on API 33+ and backported below it via this dependency.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — manages all Compose library versions together
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.navigation.compose)

    // Hilt dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager — periodic background sync fallback, see SyncWorker
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room local database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Firebase Cloud Messaging — native push (registration signed with the wallet key against
    // the KaChat indexer's /v1/push API). Only active when google-services.json is present.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Play Integrity - device attestation for the one-per-device Kaspa welcome-gift claim
    // (Android equivalent of iOS's DeviceCheck + App Attest).

    // ML Kit on-device translation for KaPosts (translate + source-language identification).
    // Both run locally against downloaded language packs; nothing is sent to a server.
    implementation(libs.mlkit.language.id)

    // DataStore (settings/preferences)
    implementation(libs.datastore.preferences)

    // Security (Keystore-backed encrypted storage)
    implementation(libs.security.crypto)

    // Biometric / device-credential prompt (seed phrase view, unlocking a saved account)
    implementation(libs.androidx.biometric)

    // Crypto (BIP39, BIP32/44)
    implementation(libs.bitcoinj.core)
    implementation(libs.bip39.kotlin)
    implementation(libs.zxing.core)

    // Camera (QR scanning)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Frosted glass behind the floating dock: Compose cannot blur what is BEHIND a composable
    // on its own (Modifier.blur blurs the composable's own content), so the backdrop is captured
    // and blurred by this - RenderEffect on Android 12+, a translucent scrim below that. Pinned
    // to the 0.7 line, which is built against Compose 1.6 like the rest of the app.
    implementation(libs.haze)

    // Image loading (KNS avatars)
    implementation(libs.coil.compose)

    // Voice and video calls (WebRTC over Nextcloud Talk's signaling) - see CallService
    implementation(libs.webrtc)

    // gRPC (Kaspa node connections)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin.lite)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.grpc.inprocess) // in-process gRPC transport for testing KaspadConnection offline
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
