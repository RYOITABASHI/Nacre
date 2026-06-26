package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * JNI binding for the Mozc native engine (libmozc.so, built via Bazel in
 * .github/workflows/build-mozc.yml). The fully-qualified class name is FIXED:
 * libmozc.so's JNI_OnLoad calls RegisterNatives against exactly this class, so it
 * must live in this package with these static native methods.
 *
 * Do NOT call these directly — go through {@code NacreMozcEngine}, which loads the
 * library, copies mozc.data out of assets, and calls {@link #onPostLoad} first.
 *
 * @see <a href="https://github.com/google/mozc/blob/master/src/android/jni/mozcjni.cc">mozcjni.cc</a>
 */
public final class MozcJNI {
    private MozcJNI() {}

    /**
     * Registers the other native methods (evalCommand/onPostLoad/getDataVersion).
     * libmozc.so has NO JNI_OnLoad — this is the only method bound by name-mangling,
     * and calling it runs RegisterNatives. MUST be called once right after
     * System.loadLibrary("mozc") and before any other method here.
     */
    public static native boolean initialize();

    /**
     * Initialize the engine. Must be called once before {@link #evalCommand}.
     *
     * @param userProfileDirectoryPath writable dir for learning / user history
     * @param dataFilePath path to mozc.data (DataManager::CreateFromFile)
     * @return true on success
     */
    public static native boolean onPostLoad(String userProfileDirectoryPath, String dataFilePath);

    /**
     * Evaluate a serialized {@code mozc.commands.Command} protobuf and return the
     * serialized {@code Output}. This is the conversion entry point.
     */
    public static native byte[] evalCommand(byte[] command);

    /** Engine data version string. */
    public static native String getDataVersion();
}
