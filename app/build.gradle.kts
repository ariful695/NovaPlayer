import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Root project must declare these plugins (apply false):
//   com.android.application                    AGP 8.9+ (compileSdk 36)
//   org.jetbrains.kotlin.android               Kotlin 2.3.x   <- drop this line if you use AGP 9 built-in Kotlin
//   org.jetbrains.kotlin.plugin.compose        same version as Kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nova.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nova.player"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Media3 ships its own consumer ProGuard rules, nothing extra needed.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- Jetpack Compose (versions managed by the BOM) ----
    // 2026.08.00+ requires AGP 9.1 / compileSdk 37; this is the latest BOM that still
    // works with AGP 8.x and compileSdk 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Pinned explicitly: the extended icon set is versioned independently of the BOM.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- AndroidX ----
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    // 2.11.0+ also requires compileSdk 37 / AGP 9.2+; 2.10.x is the last line that
    // still works with AGP 8.x and compileSdk 36.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ---- Media3 ----
    val media3 = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-extractor:$media3")   // subtitle parsers (SRT / VTT / SSA / TTML)
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // ---- Runtime permissions ----
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")
}
