plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

tasks.register("assembleNacre") {
    group = "nacre"
    description = "Build the recommended Nacre APK: the app release variant."
    dependsOn(":app:assembleNacre")
}

tasks.register("installNacre") {
    group = "nacre"
    description = "Install or update Nacre with the recommended signed release APK."
    dependsOn(":app:installNacre")
}
