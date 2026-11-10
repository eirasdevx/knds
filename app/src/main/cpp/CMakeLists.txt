# KNDS — biblioteca nativa: núcleo melonDS + puente JNI.
# Sirve para el NDK de Android y también para compilar en Linux (pruebas en el PC).
cmake_minimum_required(VERSION 3.22)
project(knds C CXX)

# melonDS sin optimizar va demasiado lento: siempre en Release, también en la variante debug
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(MELONDS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/melonDS")

# Solo el núcleo: sin interfaz Qt, sin OpenGL y sin stub de GDB
set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO_RELEASE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${MELONDS_DIR} melonDS)

add_library(knds SHARED knds_jni.cpp platform.cpp)
target_compile_features(knds PRIVATE cxx_std_17)
target_link_libraries(knds PRIVATE core)

if (ANDROID)
    find_library(log-lib log)
    target_link_libraries(knds PRIVATE ${log-lib})
else()
    find_package(JNI REQUIRED)
    target_include_directories(knds PRIVATE ${JNI_INCLUDE_DIRS})
endif()
