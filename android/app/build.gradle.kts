import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties is NOT committed. It holds machine-specific values such as
// sdk.dir and (for local testing) MAX_API_BASE_URL (the URL of your MAX backend).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Where the backend URL comes from, in priority order:
//   1. Environment variable  MAX_API_BASE_URL   (used by CI / release builds)
//   2. Gradle property       -PMAX_API_BASE_URL=... (used by CI / release builds)
//   3. android/local.properties key MAX_API_BASE_URL (used for local testing)
// Returns null when nothing is configured.
fun apiUrlFromConfig(): String? {
    val fromEnv = System.getenv("MAX_API_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
    val fromProp = (project.findProperty("MAX_API_BASE_URL") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    val fromLocal = localProps.getProperty("MAX_API_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
    return fromEnv ?: fromProp ?: fromLocal
}

// BuildConfig needs exactly one trailing slash: "https://host/" + "/api/..." must not double up.
fun withTrailingSlash(url: String) = if (url.endsWith("/")) url else "$url/"

// Debug builds keep working with zero setup: a real phone on the same Wi-Fi
// uses the PC's LAN IP from local.properties (e.g. http://192.168.1.20:3000/),
// while the emulator uses its 10.0.2.2 alias.
val devApiUrl = withTrailingSlash(apiUrlFromConfig() ?: "http://10.0.2.2:3000/")

// Release signing values come from keystore.properties (never committed).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.max.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.max.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // The backend URL is public. Secrets (AI keys, service-role key, SMTP
        // password) must NEVER be placed here: they live only on the backend.
        // Debug default is emulator-friendly; release MUST be given an explicit
        // public HTTPS URL (enforced in the release block below).
        buildConfigField("String", "API_BASE_URL", "\"$devApiUrl\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }

            // The final APK must talk to your PUBLIC backend, never to a PC on
            // your Wi-Fi. There is deliberately no localhost / LAN / emulator
            // fallback here: building a release without a real URL fails fast
            // with instructions instead of shipping a broken app.
            // (Checked only when a release task actually runs, so debug builds
            // and IDE sync keep working with zero setup.)
            val releaseTasks = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
            val releaseApiUrl = apiUrlFromConfig()
            if (releaseTasks) {
                if (releaseApiUrl.isNullOrBlank()) {
                    throw GradleException(
                        "MAX_API_BASE_URL is not set. Set your public backend URL first, e.g.:\n" +
                            "  \$env:MAX_API_BASE_URL=\"https://<your-app>.onrender.com/\"  # PowerShell\n" +
                            "  MAX_API_BASE_URL=https://<your-app>.onrender.com/ ./gradlew assembleRelease  # macOS/Linux\n" +
                            "or add MAX_API_BASE_URL=https://<your-app>.onrender.com/ to android/local.properties."
                    )
                }
                if (!releaseApiUrl.startsWith("https://")) {
                    throw GradleException("Release API_BASE_URL must be https:// (got \"$releaseApiUrl\"). LAN/emulator http:// URLs are debug-only.")
                }
            }
            // Override the debug value above for release variants only.
            if (!releaseApiUrl.isNullOrBlank()) {
                buildConfigField("String", "API_BASE_URL", "\"${withTrailingSlash(releaseApiUrl)}\"")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Session token is stored encrypted with a key held in the Android Keystore.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
