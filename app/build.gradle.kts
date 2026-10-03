import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing from keystore.properties (not in git): storeFile, storePassword, keyAlias, keyPassword.
// Without it the release APK is left unsigned; debug builds use the SDK's debug key.
val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.th3rumbl3m4t0r.jmail"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.th3rumbl3m4t0r.jmail"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
    }

    signingConfigs {
        if (signing != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) signingConfig = signingConfigs.getByName("release")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/DEPENDENCIES"
        resources.excludes += "/META-INF/LICENSE*"
        resources.excludes += "/META-INF/NOTICE*"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON tree only (no @Serializable, so no compiler plugin)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // OpenPGP (MIT)
    implementation("org.bouncycastle:bcpg-jdk18on:1.79")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // MIME parsing of what we decrypt (Apache 2)
    implementation("org.apache.james:apache-mime4j-core:0.8.11")
    implementation("org.apache.james:apache-mime4j-dom:0.8.11")

    testImplementation("junit:junit:4.13.2")
}
