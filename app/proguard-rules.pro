# Nacre IME ProGuard Rules

# Keep all IME classes (service, input, keyboard, feedback, foldable)
-keep class space.manus.nacre.ime.** { *; }

# Keep Application class
-keep class space.manus.nacre.NacreApplication { *; }

# Keep Settings Activity
-keep class space.manus.nacre.ui.settings.** { *; }

# Keep AI Services (separate processes)
-keep class space.manus.nacre.ai.** { *; }

# Keep config classes
-keep class space.manus.nacre.config.** { *; }

# Keep sherpa-onnx classes UNOBFUSCATED — the native libsherpa-onnx-jni.so
# accesses config fields by literal name via JNI GetFieldID (e.g. decodingMethod
# on OfflineRecognizerConfig). R8 renaming/removing those fields makes
# OfflineRecognizer construction throw "Failed to get field ID for decodingMethod"
# and ALL on-device ASR silently falls back to the system SpeechRecognizer.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Keep Lifecycle/ViewModel/SavedState owners
-keep class * implements androidx.lifecycle.LifecycleOwner { *; }
-keep class * implements androidx.lifecycle.ViewModelStoreOwner { *; }
-keep class * implements androidx.savedstate.SavedStateRegistryOwner { *; }

# Compose (BOM already bundles its own rules, just suppress warnings)
-dontwarn androidx.compose.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
