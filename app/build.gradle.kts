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
        versionCode = 27
        versionName = "4.0"

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
    implementation(libs.play.integrity)

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

    // Image loading (KNS avatars)
    implementation(libs.coil.compose)

    // Google Drive backup (sign-in + Drive scope authorization)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

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
