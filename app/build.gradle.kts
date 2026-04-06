import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Read developer-specific values from local.properties (gitignored).
// Copy local.properties.template to local.properties and fill in your values.
val localProperties = Properties().also { props ->
    val file = rootProject.file("local.properties")
    if (file.exists()) props.load(file.inputStream())
}

android {
    namespace = "com.techyshishy.beadmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.techyshishy.beadmanager"
        minSdk = 28
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.1"

        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${localProperties.getProperty("google_web_client_id", "")}\"",
        )
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
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
            // Sign only when the keystore env var is present (i.e., in CI).
            // Local release builds are left unsigned intentionally.
            if (System.getenv("ANDROID_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

// KSP compiler arguments
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Copy the catalog JSON from the repo root into assets before each build.
// This keeps delica-beads.json as the single source of truth at the repo root
// while making it available to the app via AssetManager at runtime.
tasks.register<Copy>("copyBeadCatalog") {
    from(rootProject.projectDir.resolve("delica-beads.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") {
    dependsOn("copyBeadCatalog")
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Adaptive layout
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room (catalog only — inventory lives in Firestore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.auth.ktx)

    // Credential Manager (Google Sign-In)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Serialization (JSON parsing for the catalog seed)
    implementation(libs.kotlinx.serialization.json)

    // DataStore (seeder guard flag)
    implementation(libs.androidx.datastore.preferences)
}
