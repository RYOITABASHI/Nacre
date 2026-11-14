// Golden-output runner for the llama.cpp pin.
//
// Replicates the production JNI generation loops in
// ime-ai/src/main/cpp/nacre_llama_jni.cpp byte-for-byte in behaviour:
//   - greedy decoding (argmax over logits; identical to
//     llama_sample_token_greedy, which returns the first maximal candidate)
//   - qwen mode  : tokenize(add_special=true,  parse_special=true),
//                  stop on EOG, prompt = full text given (caller supplies the
//                  production ChatML prompt)
//   - zenz mode  : tokenize(add_special=false, parse_special=true),
//                  stop on EOG or token id 3 or piece "</s>" (checked BEFORE
//                  appending, exactly like generateZenz)
//   - one batch for the prompt (logits on last token), then one token at a
//     time; KV cache cleared per prompt; n_threads = 4 like production.
//
// Build against either llama.cpp API generation:
//   old (b3500) : g++ -std=c++17 golden_runner.cpp -I<tree>/include -I<tree>/src
//                 -I<tree>/ggml/include <tree>/build/src/libllama.a
//                 <tree>/build/ggml/src/libggml.a -lpthread -o golden_runner_old
//   new (b5092+) : compile with -DNACRE_LLAMA_NEW_API and the new tree's
//                 include dirs / libllama.a + libggml*.a equivalents.
//
// Usage:
//   golden_runner --model MODEL.gguf --ctx 2048 --manifest MANIFEST.tsv
// MANIFEST.tsv rows: <id>\t<mode:qwen|zenz>\t<max_tokens>\t<prompt_file>
// Output (stdout), per row:
//   ## <id>
//   PROMPT_TOKENS <space-separated ids>
//   GEN_TOKENS <space-separated ids>
//   TEXT_BEGIN
//   <generated text, raw>
//   TEXT_END

#include "llama.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

static std::string read_file(const std::string & path) {
    std::ifstream in(path, std::ios::binary);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

#ifdef NACRE_LLAMA_NEW_API
using nacre_vocab = const llama_vocab *;
static nacre_vocab nacre_get_vocab(llama_model * model) { return llama_model_get_vocab(model); }
static int nacre_tokenize(nacre_vocab vocab, const std::string & text, llama_token * out, int cap, bool add_special, bool parse_special) {
    return llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out, cap, add_special, parse_special);
}
static bool nacre_is_eog(nacre_vocab vocab, llama_token tok) { return llama_vocab_is_eog(vocab, tok); }
static int nacre_to_piece(nacre_vocab vocab, llama_token tok, char * buf, int cap) {
    return llama_token_to_piece(vocab, tok, buf, cap, 0, true);
}
static void nacre_kv_clear(llama_context * ctx) { llama_memory_clear(llama_get_memory(ctx), true); }
#else
using nacre_vocab = llama_model *;
static nacre_vocab nacre_get_vocab(llama_model * model) { return model; }
static int nacre_tokenize(nacre_vocab model, const std::string & text, llama_token * out, int cap, bool add_special, bool parse_special) {
    return llama_tokenize(model, text.c_str(), (int32_t) text.size(), out, cap, add_special, parse_special);
}
static bool nacre_is_eog(nacre_vocab model, llama_token tok) { return llama_token_is_eog(model, tok); }
static int nacre_to_piece(nacre_vocab model, llama_token tok, char * buf, int cap) {
    return llama_token_to_piece(model, tok, buf, cap, 0, true);
}
static void nacre_kv_clear(llama_context * ctx) { llama_kv_cache_clear(ctx); }
#endif

int main(int argc, char ** argv) {
    std::string model_path, manifest_path;
    int n_ctx = 2048;
    for (int i = 1; i + 1 < argc; i += 2) {
        std::string k = argv[i];
        if (k == "--model") model_path = argv[i + 1];
        else if (k == "--manifest") manifest_path = argv[i + 1];
        else if (k == "--ctx") n_ctx = atoi(argv[i + 1]);
    }
    if (model_path.empty() || manifest_path.empty()) {
        fprintf(stderr, "usage: golden_runner --model M --manifest F [--ctx N]\n");
        return 2;
    }

    llama_backend_init();
    // Silence llama.cpp's own logging so stdout stays machine-diffable.
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);

#ifdef NACRE_LLAMA_NEW_API
    llama_model * model = llama_model_load_from_file(model_path.c_str(), llama_model_default_params());
#else
    llama_model * model = llama_load_model_from_file(model_path.c_str(), llama_model_default_params());
#endif
    if (!model) { fprintf(stderr, "failed to load model\n"); return 1; }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
#ifdef NACRE_LLAMA_NEW_API
    llama_context * ctx = llama_init_from_model(model, ctx_params);
#else
    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
#endif
    if (!ctx) { fprintf(stderr, "failed to create context\n"); return 1; }

    nacre_vocab vocab = nacre_get_vocab(model);

    std::istringstream manifest(read_file(manifest_path));
    std::string line;
    while (std::getline(manifest, line)) {
        if (line.empty() || line[0] == '#') continue;
        // split by tabs: id, mode, max_tokens, prompt_file
        std::vector<std::string> f;
        size_t start = 0;
        while (true) {
            size_t tab = line.find('\t', start);
            if (tab == std::string::npos) { f.push_back(line.substr(start)); break; }
            f.push_back(line.substr(start, tab - start));
            start = tab + 1;
        }
        if (f.size() != 4) { fprintf(stderr, "bad manifest row: %s\n", line.c_str()); return 1; }
        const std::string id = f[0];
        const bool zenz = f[1] == "zenz";
        const int max_tokens = atoi(f[2].c_str());
        const std::string prompt_text = read_file(f[3]);

        const int n_prompt_max = (int) prompt_text.size() + 256;
        std::vector<llama_token> tokens(n_prompt_max);
        int n_tokens = nacre_tokenize(vocab, prompt_text, tokens.data(), n_prompt_max, !zenz, true);
        if (n_tokens < 0) { fprintf(stderr, "tokenize failed for %s\n", id.c_str()); return 1; }
        tokens.resize(n_tokens);

        printf("## %s\nPROMPT_TOKENS", id.c_str());
        for (auto t : tokens) printf(" %d", t);
        printf("\n");

        nacre_kv_clear(ctx);

        llama_batch batch = llama_batch_init(n_tokens, 0, 1);
        for (int i = 0; i < n_tokens; i++) {
            batch.token[batch.n_tokens] = tokens[i];
            batch.pos[batch.n_tokens] = i;
            batch.n_seq_id[batch.n_tokens] = 1;
            batch.seq_id[batch.n_tokens][0] = 0;
            batch.logits[batch.n_tokens] = false;
            batch.n_tokens++;
        }
        batch.logits[batch.n_tokens - 1] = true;
        if (llama_decode(ctx, batch) != 0) { fprintf(stderr, "prompt eval failed for %s\n", id.c_str()); return 1; }

        std::string result;
        std::vector<llama_token> gen_tokens;
        int n_cur = n_tokens;
        for (int i = 0; i < max_tokens; i++) {
            float * logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);
#ifdef NACRE_LLAMA_NEW_API
            const int n_vocab = llama_vocab_n_tokens(vocab);
#else
            const int n_vocab = llama_n_vocab(model);
#endif
            llama_token best = 0;
            float best_logit = logits[0];
            for (llama_token tid = 1; tid < n_vocab; tid++) {
                if (logits[tid] > best_logit) { best_logit = logits[tid]; best = tid; }
            }
            const llama_token new_token = best;

            if (!zenz && nacre_is_eog(vocab, new_token)) break;

            char buf[256];
            int len = nacre_to_piece(vocab, new_token, buf, sizeof(buf));
            std::string piece = len > 0 ? std::string(buf, len) : "";

            if (zenz && (nacre_is_eog(vocab, new_token) || new_token == 3 || piece == "</s>")) break;

            result += piece;
            gen_tokens.push_back(new_token);

            batch.n_tokens = 0;
            batch.token[batch.n_tokens] = new_token;
            batch.pos[batch.n_tokens] = n_cur;
            batch.n_seq_id[batch.n_tokens] = 1;
            batch.seq_id[batch.n_tokens][0] = 0;
            batch.logits[batch.n_tokens] = true;
            batch.n_tokens++;
            n_cur++;
            if (llama_decode(ctx, batch) != 0) { fprintf(stderr, "decode failed for %s at %d\n", id.c_str(), i); break; }
        }
        llama_batch_free(batch);

        printf("GEN_TOKENS");
        for (auto t : gen_tokens) printf(" %d", t);
        printf("\nTEXT_BEGIN\n%s\nTEXT_END\n", result.c_str());
        fflush(stdout);
    }

    llama_free(ctx);
#ifdef NACRE_LLAMA_NEW_API
    llama_model_free(model);
#else
    llama_free_model(model);
#endif
    llama_backend_free();
    return 0;
}
