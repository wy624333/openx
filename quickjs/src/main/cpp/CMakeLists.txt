cmake_minimum_required(VERSION 3.22.1)

project(quickjsjni)

set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../thirdparty/quickjs")

add_library(
    quickjsjni
    SHARED
    quickjs_jni.cpp
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/dtoa.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/quickjs.c
)

target_include_directories(
    quickjsjni
    PRIVATE
    ${QUICKJS_DIR}
)

target_compile_definitions(
    quickjsjni
    PRIVATE
    _GNU_SOURCE
    NDEBUG
    CONFIG_VERSION="2026-06-04"
)

target_compile_options(
    quickjsjni
    PRIVATE
    -O3
    -fwrapv
    -funsigned-char
    -Wall
    -Wextra
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -Wno-unused-parameter
    -Wwrite-strings
    -Wchar-subscripts
)

set_target_properties(
    quickjsjni
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

add_library(
    termuxjni
    SHARED
    termux.c
)

target_compile_options(
    termuxjni
    PRIVATE
    -Os
    -fno-stack-protector
    -Wl,--gc-sections
)

set_target_properties(
    termuxjni
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

target_link_libraries(
    quickjsjni
    android
    log
    m
)

target_link_libraries(
    termuxjni
    android
    log
)
