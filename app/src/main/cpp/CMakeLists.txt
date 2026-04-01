cmake_minimum_required(VERSION 3.22.1)
project("tpan_tap" C)

add_library(tpan_tap SHARED tap_jni.c)
target_link_libraries(tpan_tap log)
