cmake_minimum_required(VERSION 3.22.1)
project(granger_taglib CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# TagLib (git submodule, LGPL-2.1/MPL-1.1) built as a static library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_BINDINGS OFF CACHE BOOL "" FORCE)
set(WITH_ZLIB OFF CACHE BOOL "" FORCE)
add_subdirectory(taglib EXCLUDE_FROM_ALL)

add_library(granger_taglib SHARED taglib_jni.cpp)
target_link_libraries(granger_taglib PRIVATE tag)
# TagLib only exports install-tree include dirs, so point at the source tree.
target_include_directories(granger_taglib PRIVATE
    taglib/taglib
    taglib/taglib/toolkit
    ${CMAKE_CURRENT_BINARY_DIR}/taglib)
# 16 KB page alignment for Android 15+ devices.
target_link_options(granger_taglib PRIVATE "-Wl,-z,max-page-size=16384")
