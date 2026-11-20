cmake_minimum_required(VERSION 3.22.1)
project(rnnoise_jni C)
add_library(rnnoise_jni SHARED
    rnnoise_jni.c
    rnnoise/denoise.c
    rnnoise/rnn.c
    rnnoise/rnn_data.c
    rnnoise/pitch.c
    rnnoise/kiss_fft.c
    rnnoise/celt_lpc.c)
target_include_directories(rnnoise_jni PRIVATE rnnoise)
target_compile_options(rnnoise_jni PRIVATE -O3 -w)
target_link_libraries(rnnoise_jni m)
