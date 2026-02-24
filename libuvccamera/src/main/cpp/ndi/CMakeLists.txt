# NDI Wrapper CMakeLists.txt for CMake-based build system

cmake_minimum_required(VERSION 3.10)

project(uvc-ndi-wrapper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Source files
set(NDI_SOURCES
    ndi/ndi.cpp
    ndi/ndi-sender.cpp
    ndi/color-conversion.cpp
)

# Library
add_library(uvc-ndi-wrapper SHARED ${NDI_SOURCES})

# Include directories
target_include_directories(uvc-ndi-wrapper PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/ndi/include
)

# Find Android logging library
find_library(log-lib log)

# Prebuilt NDI library
add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so
)

# Link libraries
target_link_libraries(uvc-ndi-wrapper
    ndi
    ${log-lib}
)
