plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "space.manus.nacre"
    compileSdk = 34

    defaultConfig {
        applicationId = "space.manus.nacre"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    // Prevent aapt2 from re-compressing already-gzipped assets
    androidResources {
        noCompress += listOf("gz", "tsv.gz", "bin")
    }
}

dependencies {
    implementation(project(":ime-core"))
    implementation(project(":ime-config"))
    implementation(project(":ime-ai"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Google Play Billing for Nacre AI addon purchase
    implementation("com.android.billingclient:billing-ktx:7.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
