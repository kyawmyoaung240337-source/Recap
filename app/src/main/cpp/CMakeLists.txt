cmake_minimum_required(VERSION 3.18)
project(kgwhisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE Release)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp ${CMAKE_BINARY_DIR}/whisper)

add_library(kgwhisper SHARED whisper_jni.cpp)
target_include_directories(kgwhisper PRIVATE
    whisper.cpp/include
    whisper.cpp/ggml/include)
target_link_libraries(kgwhisper whisper log android)
