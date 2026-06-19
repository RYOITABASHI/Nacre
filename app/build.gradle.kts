plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import org.gradle.api.GradleException
import java.util.Properties

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

fun signingValue(propertyName: String, environmentName: String): String? {
    return signingProperties.getProperty(propertyName)
        ?: providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(environmentName).orNull
}

val nacreReleaseStoreFile = signingValue("storeFile", "NACRE_RELEASE_STORE_FILE")
val nacreReleaseStorePassword = signingValue("storePassword", "NACRE_RELEASE_STORE_PASSWORD")
val nacreReleaseKeyAlias = signingValue("keyAlias", "NACRE_RELEASE_KEY_ALIAS")
val nacreReleaseKeyPassword = signingValue("keyPassword", "NACRE_RELEASE_KEY_PASSWORD")
val nacreReleaseSigningValues = listOf(
    nacreReleaseStoreFile,
    nacreReleaseStorePassword,
    nacreReleaseKeyAlias,
    nacreReleaseKeyPassword,
)
val hasAnyNacreReleaseSigning = nacreReleaseSigningValues.any { !it.isNullOrBlank() }
val hasNacreReleaseSigning = nacreReleaseSigningValues.all { !it.isNullOrBlank() }

if (hasAnyNacreReleaseSigning && !hasNacreReleaseSigning) {
    throw GradleException(
        "Incomplete Nacre release signing configuration. " +
            "Run tools/setup_nacre_release_signing.sh or provide storeFile, " +
            "storePassword, keyAlias, and keyPassword.",
    )
}

android {
    namespace = "space.manus.nacre"
    compileSdk = 34

    defaultConfig {
        applicationId = "space.manus.nacre"
        minSdk = 26
        targetSdk = 34
        // CI injects a monotonic NACRE_VERSION_CODE (e.g. 100 + run number) so the
        // in-app updater can tell builds apart. Local builds fall back to 2/0.3.0.
        versionCode = System.getenv("NACRE_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("NACRE_VERSION_NAME") ?: "0.4.1"
    }

    signingConfigs {
        if (hasNacreReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(nacreReleaseStoreFile!!)
                storePassword = nacreReleaseStorePassword
                keyAlias = nacreReleaseKeyAlias
                keyPassword = nacreReleaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasNacreReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
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

    // FileProvider for the in-app updater (serving the downloaded APK)
    implementation("androidx.core:core-ktx:1.13.1")

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

val requireNacreReleaseSigning by tasks.registering {
    group = "nacre"
    description = "Fails when the recommended signed release install configuration is missing."

    doLast {
        if (!hasNacreReleaseSigning) {
            throw GradleException(
                "Nacre release signing is not configured. " +
                    "Run tools/setup_nacre_release_signing.sh, then use ./gradlew installNacre.",
            )
        }
    }
}

tasks.register("assembleNacre") {
    group = "nacre"
    description = "Build the recommended single APK: the release variant, signed when signing.properties is configured."
    dependsOn("assembleRelease")
}

tasks.register("installNacre") {
    group = "nacre"
    description = "Install or update Nacre using the signed release variant."
    if (hasNacreReleaseSigning) {
        dependsOn("installRelease")
    } else {
        dependsOn(requireNacreReleaseSigning)
    }
}

tasks.matching { it.name == "installRelease" }.configureEach {
    dependsOn(requireNacreReleaseSigning)
}
