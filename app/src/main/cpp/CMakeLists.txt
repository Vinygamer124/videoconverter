cmake_minimum_required(VERSION 3.16)
project(aomjni C CXX)

# libaom luôn build ở Release (nếu không, bản debug của app sẽ chạy -O0 và cực chậm)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Chỉ giữ phần encoder, bỏ mọi thứ không cần
set(ENABLE_DOCS 0 CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES 0 CACHE BOOL "" FORCE)
set(ENABLE_TOOLS 0 CACHE BOOL "" FORCE)
set(ENABLE_TESTS 0 CACHE BOOL "" FORCE)
set(ENABLE_TESTDATA 0 CACHE BOOL "" FORCE)
set(CONFIG_AV1_DECODER 0 CACHE STRING "" FORCE)
set(CONFIG_AV1_HIGHBITDEPTH 0 CACHE STRING "" FORCE)
set(CONFIG_WEBM_IO 0 CACHE STRING "" FORCE)
set(CONFIG_LIBYUV 0 CACHE STRING "" FORCE)
set(CONFIG_MULTITHREAD 1 CACHE STRING "" FORCE)
set(CONFIG_PIC 1 CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS 0 CACHE BOOL "" FORCE)

add_subdirectory(libaom ${CMAKE_CURRENT_BINARY_DIR}/libaom_build EXCLUDE_FROM_ALL)

add_library(aomjni SHARED aomjni.c)
target_include_directories(aomjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libaom)
target_compile_options(aomjni PRIVATE -O3)
target_link_libraries(aomjni aom log)
