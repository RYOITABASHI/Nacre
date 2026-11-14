#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <atomic>

#define TAG "NacreLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef LLAMA_AVAILABLE
#include "llama.h"
#include <vector>

// Inline replacements for the common/ helpers we dropped. These match the
// b3500 common/common.h definitions byte-for-byte, just hoisted into this
// translation unit so we don't need to pull in nlohmann/json & friends.
static inline void nacre_batch_add(
    llama_batch & batch,
    llama_token id,
    llama_pos pos,
    llama_seq_id seq_id,
    bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id  [batch.n_tokens][0] = seq_id;
    batch.logits  [batch.n_tokens] = logits;
    batch.n_tokens++;
}
static inline void nacre_batch_clear(llama_batch & batch) {
    batch.n_tokens = 0;
}
#endif

static std::mutex g_mutex;

#ifdef LLAMA_AVAILABLE
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
#endif
static bool g_model_loaded = false;
static std::atomic<bool> g_cancel{false};

// --- zenz neural conversion model ---
// Separate model/context handle set so the zenz converter can stay resident
// alongside the Qwen cleanup LLM above. Guarded by the same g_mutex; zenz
// generation is short (~1s) and conversion requests are infrequent, so
// sharing the mutex with Qwen generation is acceptable for v1.
#ifdef LLAMA_AVAILABLE
static llama_model* g_zenz_model = nullptr;
static llama_context* g_zenz_ctx = nullptr;
#endif
static bool g_zenz_loaded = false;
static std::atomic<bool> g_zenz_cancel{false};
// Last zenz load failure, surfaced to Kotlin so the Settings screen can show
// it — a model that fails to load must never fail silently.
static std::string g_zenz_load_error;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_LlamaJni_loadModel(JNIEnv* env, jobject, jstring model_path) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    // Unload existing
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading LLM model from: %s", path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only on mobile

    g_model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load LLM model");
        g_model_loaded = false;
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create LLM context");
        llama_free_model(g_model);
        g_model = nullptr;
        g_model_loaded = false;
        return JNI_FALSE;
    }

    g_model_loaded = true;
    LOGI("LLM model loaded successfully");
    return JNI_TRUE;
#else
    LOGE("llama.cpp not available (stub build)");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_LlamaJni_unloadModel(JNIEnv*, jobject) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
#endif
    g_model_loaded = false;
    LOGI("LLM model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_LlamaJni_isModelLoaded(JNIEnv*, jobject) {
    return g_model_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_LlamaJni_cancelGeneration(JNIEnv*, jobject) {
    g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_space_manus_nacre_ai_LlamaJni_generate(JNIEnv* env, jobject, jstring prompt_str, jint max_tokens) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(false);

    if (!g_ctx || !g_model) {
        return env->NewStringUTF("");
    }

    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt_text(prompt);
    env->ReleaseStringUTFChars(prompt_str, prompt);

    // Tokenize
    const int n_prompt_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(g_model, prompt_text.c_str(), prompt_text.length(),
                                   tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // Clear KV cache
    llama_kv_cache_clear(g_ctx);

    // Evaluate prompt
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        nacre_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Prompt eval failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Generate
    std::string result;
    int n_cur = n_tokens;

    for (int i = 0; i < max_tokens; i++) {
        if (g_cancel.load()) {
            LOGI("Generation cancelled");
            break;
        }

        // Build candidates array from logits for greedy sampling.
        // b3500 API: llama_sample_token_greedy(ctx, candidates_arr*) — no 3-arg form exists.
        float* logits = llama_get_logits_ith(g_ctx, batch.n_tokens - 1);
        const int n_vocab = llama_n_vocab(g_model);
        std::vector<llama_token_data> candidates;
        candidates.reserve(n_vocab);
        for (llama_token tid = 0; tid < n_vocab; tid++) {
            candidates.push_back(llama_token_data{tid, logits[tid], 0.0f});
        }
        llama_token_data_array candidates_arr = { candidates.data(), candidates.size(), false };
        llama_token new_token = llama_sample_token_greedy(g_ctx, &candidates_arr);

        if (llama_token_is_eog(g_model, new_token)) break;

        char buf[256];
        int len = llama_token_to_piece(g_model, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
        }

        // Prepare next batch
        nacre_batch_clear(batch);
        nacre_batch_add(batch, new_token, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at token %d", i);
            break;
        }
    }

    llama_batch_free(batch);
    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
#else
    return env->NewStringUTF("");
#endif
}

// ---------------------------------------------------------------------------
// zenz neural conversion (gpt2-arch GGUF, pre-tokenizer
// "gpt2-small-japanese-char" — enabled by the configure-time patch in
// CMakeLists.txt). Prompt format per azooKey zenzai docs:
//   U+EE00 + <katakana reading> + U+EE01, generate until </s>.
// Differences from generate() above, both deliberate:
//  - tokenize with add_special=false: the reference tokenization (azooKey
//    fork) has no BOS for this model; add_special=true would be a no-op for
//    qwen2 but is not assumed here.
//  - stop conditions: this GGUF's GGUF metadata says eos=<s> (id 2), but the
//    token zenz actually emits at end of conversion is </s> (id 3), which
//    llama_token_is_eog does NOT flag under the stock vocab setup (the
//    azooKey fork hardcodes eos=3 for gpt2 tokenizers instead). Stop
//    explicitly on id 3 / piece "</s>" as well, or generation runs on into
//    hallucinated follow-up conversions (observed on desktop).
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_LlamaJni_loadZenzModel(JNIEnv* env, jobject, jstring model_path) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_zenz_ctx) { llama_free(g_zenz_ctx); g_zenz_ctx = nullptr; }
    if (g_zenz_model) { llama_free_model(g_zenz_model); g_zenz_model = nullptr; }
    g_zenz_loaded = false;

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading zenz model from: %s", path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only on mobile

    g_zenz_model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_zenz_model) {
        LOGE("Failed to load zenz model");
        g_zenz_load_error = "llama_load_model_from_file failed (unsupported pre-tokenizer or corrupt GGUF)";
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024; // zenz-v3.2-small context size
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    g_zenz_ctx = llama_new_context_with_model(g_zenz_model, ctx_params);
    if (!g_zenz_ctx) {
        LOGE("Failed to create zenz context");
        llama_free_model(g_zenz_model);
        g_zenz_model = nullptr;
        g_zenz_load_error = "llama_new_context_with_model failed";
        return JNI_FALSE;
    }

    g_zenz_loaded = true;
    g_zenz_load_error.clear();
    LOGI("zenz model loaded successfully");
    return JNI_TRUE;
#else
    g_zenz_load_error = "llama.cpp not available (stub build)";
    LOGE("llama.cpp not available (stub build)");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_LlamaJni_unloadZenzModel(JNIEnv*, jobject) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_zenz_ctx) { llama_free(g_zenz_ctx); g_zenz_ctx = nullptr; }
    if (g_zenz_model) { llama_free_model(g_zenz_model); g_zenz_model = nullptr; }
#endif
    g_zenz_loaded = false;
    LOGI("zenz model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_LlamaJni_isZenzModelLoaded(JNIEnv*, jobject) {
    return g_zenz_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_space_manus_nacre_ai_LlamaJni_getZenzLoadError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_zenz_load_error.c_str());
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_LlamaJni_cancelZenzGeneration(JNIEnv*, jobject) {
    g_zenz_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_space_manus_nacre_ai_LlamaJni_generateZenz(JNIEnv* env, jobject, jstring prompt_str, jint max_tokens) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    g_zenz_cancel.store(false);

    if (!g_zenz_ctx || !g_zenz_model) {
        return env->NewStringUTF("");
    }

    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt_text(prompt);
    env->ReleaseStringUTFChars(prompt_str, prompt);

    // Tokenize: add_special=false (see header comment), parse_special=true so
    // the U+EE00/U+EE01 controls and any special tokens map exactly as in the
    // azooKey reference implementation.
    const int n_prompt_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(g_zenz_model, prompt_text.c_str(), prompt_text.length(),
                                   tokens.data(), n_prompt_max, false, true);
    if (n_tokens < 0) {
        LOGE("zenz tokenization failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    llama_kv_cache_clear(g_zenz_ctx);

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        nacre_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_zenz_ctx, batch) != 0) {
        LOGE("zenz prompt eval failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    std::string result;
    int n_cur = n_tokens;

    for (int i = 0; i < max_tokens; i++) {
        if (g_zenz_cancel.load()) {
            LOGI("zenz generation cancelled");
            break;
        }

        // Greedy sampling, same candidates-array pattern as generate().
        float* logits = llama_get_logits_ith(g_zenz_ctx, batch.n_tokens - 1);
        const int n_vocab = llama_n_vocab(g_zenz_model);
        std::vector<llama_token_data> candidates;
        candidates.reserve(n_vocab);
        for (llama_token tid = 0; tid < n_vocab; tid++) {
            candidates.push_back(llama_token_data{tid, logits[tid], 0.0f});
        }
        llama_token_data_array candidates_arr = { candidates.data(), candidates.size(), false };
        llama_token new_token = llama_sample_token_greedy(g_zenz_ctx, &candidates_arr);

        char buf[256];
        int len = llama_token_to_piece(g_zenz_model, new_token, buf, sizeof(buf), 0, true);
        std::string piece = len > 0 ? std::string(buf, len) : "";

        // Stop BEFORE appending: stock is_eog (id 2), plus zenz's real stop
        // token </s> (id 3 / piece "</s>") — see header comment.
        if (llama_token_is_eog(g_zenz_model, new_token) || new_token == 3 || piece == "</s>") break;

        result += piece;

        nacre_batch_clear(batch);
        nacre_batch_add(batch, new_token, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(g_zenz_ctx, batch) != 0) {
            LOGE("zenz decode failed at token %d", i);
            break;
        }
    }

    llama_batch_free(batch);
    LOGI("zenz generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
#else
    return env->NewStringUTF("");
#endif
}

} // extern "C"
