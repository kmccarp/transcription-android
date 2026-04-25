# CMake build for the JNI bridge to whisper.cpp.
#
# We pull a pinned whisper.cpp tag at configure time via FetchContent so the
# repo doesn't have to vendor the full source tree. CI provides network
# access; if you build offline, run `./gradlew :app:downloadWhisperModel`
# at least once with network and the FetchContent cache is reused after.
cmake_minimum_required(VERSION 3.22.1)
project(transcription_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Stop whisper.cpp from building its CLI demos and test suite.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
        whisper_cpp
        GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
        GIT_TAG        v1.7.6
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(transcription_jni SHARED whisper_jni.cpp)

target_include_directories(transcription_jni PRIVATE
        ${whisper_cpp_SOURCE_DIR}/include
        ${whisper_cpp_SOURCE_DIR}/ggml/include)

target_link_libraries(transcription_jni
        whisper
        android
        log)
