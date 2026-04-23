cmake_minimum_required(VERSION 3.22.1)
project(trafy_vision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 16 KB page-size alignment is required by Google Play on Android 15+
# (enforced from Nov 2025, extended to May 31 2026). Apply to any .so we emit
# and to the final link — the shared object stays aligned even if a future
# dependency isn't.
add_link_options("-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# NCNN prebuilt (populated by scripts/setup-ncnn.sh).
# This CMakeLists is only invoked when ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}
# exists — app/build.gradle.kts guards the externalNativeBuild block with a
# filesystem check so repos without the blob still build.
# ---------------------------------------------------------------------------
set(NCNN_ROOT "${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}")
if (NOT EXISTS "${NCNN_ROOT}/lib/libncnn.a")
    message(FATAL_ERROR
        "NCNN prebuilt not found at ${NCNN_ROOT}/lib/libncnn.a. "
        "Run scripts/setup-ncnn.sh before building the native layer.")
endif()

add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${NCNN_ROOT}/lib/libncnn.a"
    INTERFACE_INCLUDE_DIRECTORIES "${NCNN_ROOT}/include/ncnn")

# glslang pieces ship in the Vulkan prebuilt for shader compilation.
set(GLSLANG_LIB_DIR "${NCNN_ROOT}/lib")
set(GLSLANG_LIBS
    glslang
    MachineIndependent
    GenericCodeGen
    OGLCompiler
    OSDependent
    SPIRV)
foreach (lib ${GLSLANG_LIBS})
    set(full "${GLSLANG_LIB_DIR}/lib${lib}.a")
    if (EXISTS "${full}")
        add_library(${lib} STATIC IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION "${full}")
        list(APPEND NCNN_VULKAN_LIBS ${lib})
    endif()
endforeach()

# ---------------------------------------------------------------------------
# libtrafy_vision.so — the JNI boundary for Kotlin's NcnnBridge.
# ---------------------------------------------------------------------------
add_library(trafy_vision SHARED
    yolo.cpp
    yolo_jni.cpp)

target_include_directories(trafy_vision PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# NCNN is built with OpenMP enabled, and because it's a .a (static), the OpenMP
# runtime must be provided at the final link. The NDK ships both a dynamic and
# a static copy of libomp — statically linking keeps libtrafy_vision.so
# self-contained (no libomp.so runtime dependency surprise).
# -fno-rtti: NCNN's DataReader class is defined without RTTI, so anything
# subclassing it would fail with "typeinfo for ncnn::DataReader" at link time.
# We sidestep that by loading models from memory instead of subclassing, but
# the flag still matters for any future cross-inheritance and trims binary size.
target_compile_options(trafy_vision PRIVATE -fopenmp -fno-rtti)
target_link_options(trafy_vision PRIVATE -fopenmp -static-openmp)

target_link_libraries(trafy_vision
    PRIVATE
        ncnn
        ${NCNN_VULKAN_LIBS}
        vulkan
        android
        jnigraphics
        log)
