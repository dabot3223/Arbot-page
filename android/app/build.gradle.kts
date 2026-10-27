import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Build-time configuration comes from android/local.properties (git-ignored)
// or environment variables in CI. Only PUBLIC values belong in the app: the
// API URL, the envelope public key, TLS pins and the Cloud project number.
// Server secrets (envelope private key, service account) never go here.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cfg(key: String, env: String, default: String = ""): String =
    listOf(localProps.getProperty(key), System.getenv(env), providers.gradleProperty(key).orNull)
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: default

fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.arbot.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arbot.game"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // e.g. https://<project-ref>.supabase.co/functions/v1/api
        buildConfigField("String", "API_URL", quoted(cfg("arbot.apiUrl", "ARBOT_API_URL")))
        buildConfigField("String", "API_KEY_ID", quoted(cfg("arbot.apiKeyId", "ARBOT_API_KEY_ID", "k1")))
        buildConfigField("String", "API_PUBLIC_KEY", quoted(cfg("arbot.apiPublicKey", "ARBOT_API_PUBLIC_KEY")))
        // Comma separated OkHttp pins, e.g. "sha256/AAAA…=,sha256/BBBB…=" (include a backup pin).
        buildConfigField("String", "CERT_PINS", quoted(cfg("arbot.certPins", "ARBOT_CERT_PINS")))
        buildConfigField("long", "CLOUD_PROJECT_NUMBER", cfg("arbot.cloudProjectNumber", "ARBOT_CLOUD_PROJECT_NUMBER", "0") + "L")
    }

    signingConfigs {
        val storeFile = cfg("arbot.signing.storeFile", "ARBOT_SIGNING_STORE_FILE")
        if (storeFile.isNotEmpty()) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = cfg("arbot.signing.storePassword", "ARBOT_SIGNING_STORE_PASSWORD")
                keyAlias = cfg("arbot.signing.keyAlias", "ARBOT_SIGNING_KEY_ALIAS")
                keyPassword = cfg("arbot.signing.keyPassword", "ARBOT_SIGNING_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.arbot:arbot-core")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.billing)
    implementation(libs.play.integrity)
    implementation(libs.play.services.tasks)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
