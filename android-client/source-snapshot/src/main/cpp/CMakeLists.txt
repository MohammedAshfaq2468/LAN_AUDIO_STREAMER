cmake_minimum_required(VERSION 3.22.1)

project("wifiaudiospeaker")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Standard debug and release flags
# Using CMAKE_<LANG>_FLAGS_... is preferred over CMAKE_<LANG>_FLAGS.
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O0 -g")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -ffast-math -DNDEBUG")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O0 -g")

# Create native library
add_library(
        wifiaudiospeaker
        SHARED
        wifi_audio_native.cpp
        network_handler.cpp
        audio_processor.cpp
)

# Link required Android and logging libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        wifiaudiospeaker
        ${log-lib}
        ${android-lib}
)

# Standard and platform-specific optimizations for modern CMake
# This automatically handles the correct architecture and NEON support through the NDK toolchain.
target_compile_options(wifiaudiospeaker PRIVATE
        $<$<CONFIG:Release>:-ffunction-sections -fdata-sections -flto>
        $<$<CONFIG:Debug>:-fno-omit-frame-pointer>
)

# Standard linker optimizations for release builds
target_link_options(wifiaudiospeaker PRIVATE
        $<$<CONFIG:Release>:-Wl,--gc-sections -flto>
        $<$<CONFIG:Release>:-Wl,--strip-all>
)

# General, non-device-specific compile-time definitions
target_compile_definitions(wifiaudiospeaker PRIVATE
        NATIVE_AUDIO_STREAMING=1
        ANDROID_AUDIO_STREAMING=1
        RING_BUFFER_STREAMING=1
        # Add other general definitions as needed
)

# Include directories for project source files
target_include_directories(wifiaudiospeaker PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# Enhanced build information (without device-specific details)
message(STATUS "==========================================")
message(STATUS "WiFi Audio Speaker - Build Configuration")
message(STATUS "==========================================")
message(STATUS "Target ABI: ${ANDROID_ABI}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Build configuration complete!")
message(STATUS "==========================================")