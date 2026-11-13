cmake_minimum_required(VERSION 3.22)
project(arm32fps LANGUAGES CXX)

find_package(shadowhook REQUIRED CONFIG)

add_library(arm32fps SHARED arm32fps.cpp)
target_compile_features(arm32fps PRIVATE cxx_std_17)
target_link_libraries(arm32fps PRIVATE shadowhook::shadowhook EGL log)
target_link_options(arm32fps PRIVATE -Wl,-z,max-page-size=16384)
