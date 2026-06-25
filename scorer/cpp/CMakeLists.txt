cmake_minimum_required(VERSION 3.16)
project(spectre_scorer CXX C)

# KenLM source — bundled as a git submodule at scorer/kenlm/
set(KENLM_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../kenlm)
set(KENLM_MAX_ORDER 6)

add_compile_definitions(KENLM_MAX_ORDER=${KENLM_MAX_ORDER})
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_C_STANDARD 11)

find_package(Threads REQUIRED)
find_package(ZLIB REQUIRED)

option(WITH_BZIP2 "Enable bzip2 support in read_compressed" ON)
option(WITH_LZMA  "Enable lzma support in read_compressed"  ON)

if(WITH_BZIP2)
    find_library(BZ2_LIB bz2)
    if(BZ2_LIB)
        add_compile_definitions(HAVE_ZLIB)
        list(APPEND EXTRA_LIBS ${BZ2_LIB})
    endif()
endif()
if(WITH_LZMA)
    find_library(LZMA_LIB lzma)
    if(LZMA_LIB)
        add_compile_definitions(HAVE_XZLIB)
        list(APPEND EXTRA_LIBS ${LZMA_LIB})
    endif()
endif()

set(UTIL ${KENLM_SRC}/util)
set(UTIL_SRCS
    ${UTIL}/bit_packing.cc
    ${UTIL}/ersatz_progress.cc
    ${UTIL}/exception.cc
    ${UTIL}/file.cc
    ${UTIL}/file_piece.cc
    ${UTIL}/float_to_string.cc
    ${UTIL}/integer_to_string.cc
    ${UTIL}/mmap.cc
    ${UTIL}/murmur_hash.cc
    ${UTIL}/parallel_read.cc
    ${UTIL}/pool.cc
    ${UTIL}/read_compressed.cc
    ${UTIL}/scoped.cc
    ${UTIL}/spaces.cc
    ${UTIL}/string_piece.cc
    ${UTIL}/usage.cc
    ${UTIL}/double-conversion/bignum-dtoa.cc
    ${UTIL}/double-conversion/bignum.cc
    ${UTIL}/double-conversion/cached-powers.cc
    ${UTIL}/double-conversion/fast-dtoa.cc
    ${UTIL}/double-conversion/fixed-dtoa.cc
    ${UTIL}/double-conversion/strtod.cc
    ${UTIL}/double-conversion/double-to-string.cc
    ${UTIL}/double-conversion/string-to-double.cc
)
if(ANDROID)
    list(APPEND UTIL_SRCS ${UTIL}/getopt.c)
endif()

add_library(kenlm_util STATIC ${UTIL_SRCS})
target_include_directories(kenlm_util PUBLIC ${KENLM_SRC})
target_link_libraries(kenlm_util PUBLIC Threads::Threads ZLIB::ZLIB ${EXTRA_LIBS})

set(LM ${KENLM_SRC}/lm)
set(LM_SRCS
    ${LM}/bhiksha.cc
    ${LM}/binary_format.cc
    ${LM}/config.cc
    ${LM}/lm_exception.cc
    ${LM}/model.cc
    ${LM}/quantize.cc
    ${LM}/read_arpa.cc
    ${LM}/search_hashed.cc
    ${LM}/search_trie.cc
    ${LM}/sizes.cc
    ${LM}/trie.cc
    ${LM}/trie_sort.cc
    ${LM}/value_build.cc
    ${LM}/virtual_interface.cc
    ${LM}/vocab.cc
)
add_library(kenlm STATIC ${LM_SRCS})
target_include_directories(kenlm PUBLIC ${KENLM_SRC})
target_link_libraries(kenlm PUBLIC kenlm_util)

# JNI shared library loaded by KenLmScorer.kt
add_library(spectre_score SHARED spectre_jni.cc)
target_include_directories(spectre_score PRIVATE ${KENLM_SRC})
target_link_libraries(spectre_score PRIVATE kenlm kenlm_util)

# Original stdin/stdout subprocess binary (reference / host testing)
add_executable(spectre_score_bin spectre_score.cc)
set_target_properties(spectre_score_bin PROPERTIES OUTPUT_NAME spectre_score_standalone)
target_include_directories(spectre_score_bin PRIVATE ${KENLM_SRC})
target_link_libraries(spectre_score_bin PRIVATE kenlm kenlm_util)
