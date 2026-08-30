cmake_minimum_required(VERSION 3.16)
project(localOcr LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Python3 REQUIRED COMPONENTS Development)
find_package(JNI REQUIRED)

add_library(localOcr SHARED
    src/main/cpp/local_ocr.cpp
)

target_include_directories(localOcr PRIVATE
    ${Python3_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(localOcr PRIVATE
    Python3::Python
    ${CMAKE_DL_LIBS}
)

if(MSVC)
    target_compile_options(localOcr PRIVATE /W4)
else()
    target_compile_options(localOcr PRIVATE -Wall -Wextra -Wpedantic)
endif()
