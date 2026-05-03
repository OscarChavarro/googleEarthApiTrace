cmake_minimum_required(VERSION 3.10)

project(traceLogSplitter LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(traceLogSplitter
    src/splitter.cpp
)

if(MSVC)
    target_compile_options(traceLogSplitter PRIVATE /W4)
else()
    target_compile_options(traceLogSplitter PRIVATE -Wall -Wextra -Wpedantic)
endif()
