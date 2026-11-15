cmake_minimum_required(VERSION 3.22)
project(ft8ok_dsp C)

set(CMAKE_C_STANDARD 11)

# ft8_lib (MIT) with KISS FFT (BSD-3-Clause) embedded in its fft/ directory.
# Vendored at upstream commit 9fec6ca39886edbf96f4f5e71edc76da5074e871.
# See ../../../../THIRD_PARTY_LICENSES.md and ft8_lib/LICENSE.
#
# Only the library sources are vendored - not ft8_lib's demo/ or test/, which
# contain main() functions and host-only audio code.
file(GLOB FT8_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib/ft8/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib/fft/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib/common/monitor.c"
)

add_library(ft8ok_dsp SHARED
    ft8ok_decoder.c
    ft8ok_subtract.c
    ft8ok_replies.c
    ${FT8_SOURCES}
)

target_include_directories(ft8ok_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib
)

# -O2 not -O3: the decoder is the hot path but we would rather have a build that
# behaves predictably than chase the last few percent before anything is measured.
target_compile_options(ft8ok_dsp PRIVATE -O2 -Wall)

# Android's bionic provides stpcpy, which ft8_lib/ft8/message.c calls
# unconditionally. A host (desktop) build needs a shim for it (see ADR 0004); the
# NDK build does not.

target_link_libraries(ft8ok_dsp m log)
