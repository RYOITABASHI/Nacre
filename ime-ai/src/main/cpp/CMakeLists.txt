cmake_minimum_required(VERSION 3.22)
project(nacre-ai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# --- llama.cpp ---
# Source: https://github.com/ggml-org/llama.cpp (pinned tag: see
# tools/llama-pin.txt; the CI step clones the tag and copies the whole
# tree into ime-ai/src/main/cpp/llama.cpp/). Built via upstream's own
# CMake (add_subdirectory) — the pre-b12381 flat-file flattening cannot
# cover the modern multi-file src/ + ggml layout.
set(LLAMA_SRC_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)
if(EXISTS ${LLAMA_SRC_DIR}/CMakeLists.txt)
    # --- zenz pre-tokenizer patch (configure-time, additive) ---
    # Same patch as the b3500 era, retargeted: the pre-tokenizer dispatch
    # moved from llama.cpp (b3500: src/llama.cpp) to src/llama-vocab.cpp.
    # The anchor string is byte-identical. Fail loudly if it vanishes.
    set(NACRE_VOCAB_SRC ${LLAMA_SRC_DIR}/src/llama-vocab.cpp)
    file(READ ${NACRE_VOCAB_SRC} NACRE_VOCAB_CPP)
    set(NACRE_PRETOK_ANCHOR "                    tokenizer_pre == \"gpt-2\"   ||")
    string(FIND "${NACRE_VOCAB_CPP}" "${NACRE_PRETOK_ANCHOR}" NACRE_PRETOK_POS)
    if(NACRE_PRETOK_POS EQUAL -1)
        message(FATAL_ERROR
            "zenz pre-tokenizer patch: anchor not found in llama-vocab.cpp — "
            "the pinned llama.cpp changed its pre-tokenizer dispatch; "
            "re-check the patch against the new source before building.")
    endif()
    string(REPLACE "${NACRE_PRETOK_ANCHOR}"
        "${NACRE_PRETOK_ANCHOR}\n                    tokenizer_pre == \"gpt2-small-japanese-char\" ||"
        NACRE_VOCAB_CPP_PATCHED "${NACRE_VOCAB_CPP}")
    file(WRITE ${NACRE_VOCAB_SRC} "${NACRE_VOCAB_CPP_PATCHED}")
    unset(NACRE_VOCAB_CPP)
    unset(NACRE_VOCAB_CPP_PATCHED)

    # Static, library-only build of llama.cpp inside libnacre-ai.so.
    set(BUILD_SHARED_LIBS OFF)
    set(LLAMA_BUILD_COMMON OFF)
    set(LLAMA_BUILD_TESTS OFF)
    set(LLAMA_BUILD_TOOLS OFF)
    set(LLAMA_BUILD_EXAMPLES OFF)
    set(LLAMA_BUILD_SERVER OFF)
    set(LLAMA_BUILD_APP OFF)
    add_subdirectory(${LLAMA_SRC_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-build EXCLUDE_FROM_ALL)
    set(LLAMA_AVAILABLE TRUE)
else()
    message(WARNING "llama.cpp sources not found at ${LLAMA_SRC_DIR}. Building stub.")
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
