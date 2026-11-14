cmake_minimum_required(VERSION 3.22)
project(nacre-ai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# --- llama.cpp ---
# Source: https://github.com/ggerganov/llama.cpp
# Place llama.cpp source in ime-ai/src/main/cpp/llama/
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama)
if(EXISTS ${LLAMA_DIR}/llama.cpp)
    # --- zenz pre-tokenizer patch (configure-time, additive) ---
    # The zenz GGUF declares tokenizer pre-type "gpt2-small-japanese-char",
    # which upstream llama.cpp (any version, incl. the pinned b3500) rejects
    # with "unknown pre-tokenizer type". The azooKey fork (b4846) supports it
    # by mapping that string to the standard GPT-2 pre-tokenizer — the fork's
    # BPE regex for the new type is byte-identical to GPT-2's, and zenz's
    # tokenizer is char-level GPT-2 BPE, so the mapping is exact (verified on
    # desktop: token ids and greedy outputs match the fork for reference
    # sentences). Port ONLY that mapping: read the flattened llama.cpp,
    # extend the existing "gpt-2" dispatch condition, and compile the patched
    # copy from the binary dir. The new branch can only trigger for models
    # whose GGUF pre string is exactly "gpt2-small-japanese-char"; qwen2 /
    # gpt-2 models never take it. Fail the build loudly if the anchor is
    # missing (e.g. after a future llama.cpp pin bump) instead of silently
    # shipping a build where zenz cannot load.
    set(LLAMA_MAIN_SRC ${LLAMA_DIR}/llama.cpp)
    file(READ ${LLAMA_DIR}/llama.cpp NACRE_LLAMA_CPP)
    set(NACRE_PRETOK_ANCHOR "                    tokenizer_pre == \"gpt-2\"   ||")
    string(FIND "${NACRE_LLAMA_CPP}" "${NACRE_PRETOK_ANCHOR}" NACRE_PRETOK_POS)
    if(NACRE_PRETOK_POS EQUAL -1)
        message(FATAL_ERROR
            "zenz pre-tokenizer patch: anchor not found in llama.cpp — "
            "the pinned llama.cpp changed its pre-tokenizer dispatch; "
            "re-check the patch against the new source before building.")
    endif()
    string(REPLACE "${NACRE_PRETOK_ANCHOR}"
        "${NACRE_PRETOK_ANCHOR}\n                    tokenizer_pre == \"gpt2-small-japanese-char\" ||"
        NACRE_LLAMA_CPP_PATCHED "${NACRE_LLAMA_CPP}")
    set(NACRE_LLAMA_CPP_PATCHED_FILE ${CMAKE_CURRENT_BINARY_DIR}/llama-zenz.cpp)
    file(WRITE ${NACRE_LLAMA_CPP_PATCHED_FILE} "${NACRE_LLAMA_CPP_PATCHED}")
    set(LLAMA_MAIN_SRC ${NACRE_LLAMA_CPP_PATCHED_FILE})
    unset(NACRE_LLAMA_CPP)
    unset(NACRE_LLAMA_CPP_PATCHED)

    # Core llama.cpp + ggml only. nacre_llama_jni.cpp does not depend on
    # common/ (which would pull nlohmann json, a build_info generator, etc.).
    # If unicode*.cpp were copied by the CI flatten step, include them.
    set(LLAMA_CORE_SRCS
        ${LLAMA_MAIN_SRC}
        ${LLAMA_DIR}/llama-grammar.cpp
        ${LLAMA_DIR}/llama-sampling.cpp
        ${LLAMA_DIR}/llama-vocab.cpp
        ${LLAMA_DIR}/ggml.c
        ${LLAMA_DIR}/ggml-alloc.c
        ${LLAMA_DIR}/ggml-backend.c
        ${LLAMA_DIR}/ggml-quants.c
    )
    if(EXISTS ${LLAMA_DIR}/ggml-aarch64.c)
        # ARM64 SIMD kernels — required on arm64-v8a Android for Q4_0 quantization.
        list(APPEND LLAMA_CORE_SRCS ${LLAMA_DIR}/ggml-aarch64.c)
    endif()
    if(EXISTS ${LLAMA_DIR}/unicode.cpp)
        list(APPEND LLAMA_CORE_SRCS ${LLAMA_DIR}/unicode.cpp)
    endif()
    if(EXISTS ${LLAMA_DIR}/unicode-data.cpp)
        list(APPEND LLAMA_CORE_SRCS ${LLAMA_DIR}/unicode-data.cpp)
    endif()
    add_library(llama STATIC ${LLAMA_CORE_SRCS})
    target_include_directories(llama PUBLIC ${LLAMA_DIR})
    target_compile_definitions(llama PRIVATE
        GGML_USE_CPU
        NDEBUG
    )
    set(LLAMA_AVAILABLE TRUE)
else()
    message(WARNING "llama.cpp sources not found at ${LLAMA_DIR}. Building stub.")
    set(LLAMA_AVAILABLE FALSE)
endif()

# --- KenLM ---
# Source: https://github.com/kpu/kenlm
# Place kenlm source (lm/ + util/) in ime-ai/src/main/cpp/kenlm/
set(KENLM_DIR ${CMAKE_SOURCE_DIR}/kenlm)
if(EXISTS ${KENLM_DIR}/lm/model.cc)
    # Collect source files, excluding tests, mains, and benchmarks
    file(GLOB KENLM_LM_SRCS ${KENLM_DIR}/lm/*.cc)
    file(GLOB KENLM_UTIL_SRCS ${KENLM_DIR}/util/*.cc)
    file(GLOB KENLM_DCONV_SRCS ${KENLM_DIR}/util/double-conversion/*.cc)

    list(FILTER KENLM_LM_SRCS EXCLUDE REGEX ".*_main\\.cc$")
    list(FILTER KENLM_LM_SRCS EXCLUDE REGEX ".*_test\\.cc$")
    list(FILTER KENLM_LM_SRCS EXCLUDE REGEX ".*benchmark.*\\.cc$")
    list(FILTER KENLM_UTIL_SRCS EXCLUDE REGEX ".*_main\\.cc$")
    list(FILTER KENLM_UTIL_SRCS EXCLUDE REGEX ".*_test\\.cc$")
    list(FILTER KENLM_UTIL_SRCS EXCLUDE REGEX ".*benchmark.*\\.cc$")

    add_library(kenlm STATIC
        ${KENLM_LM_SRCS}
        ${KENLM_UTIL_SRCS}
        ${KENLM_DCONV_SRCS}
    )
    target_include_directories(kenlm PUBLIC ${KENLM_DIR})
    target_compile_definitions(kenlm PUBLIC
        KENLM_MAX_ORDER=5
        _LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION
    )
    target_compile_definitions(kenlm PRIVATE
        HAVE_POSIX_MEMALIGN
        NDEBUG
    )
    target_compile_options(kenlm PRIVATE -Wno-unused-parameter -Wno-sign-compare)
    set(KENLM_AVAILABLE TRUE)
else()
    message(WARNING "KenLM sources not found at ${KENLM_DIR}. Building stub.")
    set(KENLM_AVAILABLE FALSE)
endif()

# --- JNI Bridge ---
add_library(nacre-ai SHARED
    nacre_llama_jni.cpp
    nacre_kenlm_jni.cpp
)

target_include_directories(nacre-ai PRIVATE
    ${CMAKE_SOURCE_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(nacre-ai
    ${log-lib}
    ${android-lib}
)

if(LLAMA_AVAILABLE)
    target_link_libraries(nacre-ai llama)
    target_compile_definitions(nacre-ai PRIVATE LLAMA_AVAILABLE)
endif()

if(KENLM_AVAILABLE)
    target_link_libraries(nacre-ai kenlm)
    target_compile_definitions(nacre-ai PRIVATE KENLM_AVAILABLE)
endif()
