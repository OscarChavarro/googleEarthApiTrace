message (STATUS "Using CMake ${CMAKE_MAJOR_VERSION}.${CMAKE_MINOR_VERSION}.${CMAKE_PATCH_VERSION}")

cmake_minimum_required (VERSION 3.15.0)

cmake_policy (SET CMP0091 NEW)
cmake_policy (SET CMP0042 NEW)
cmake_policy (SET CMP0048 NEW)

project (apitrace)

if (WIN32 OR APPLE)
    message (FATAL_ERROR "This trimmed tree supports Linux builds for apitrace/glxtrace/egltrace only.")
endif ()

option (ENABLE_LTO "Enable link-time optimization (IPO/LTO)" OFF)
set (CMAKE_INTERPROCEDURAL_OPTIMIZATION ${ENABLE_LTO})

option (ENABLE_CLI "Enable command line interface." ON)
option (ENABLE_X11 "Enable X11 support." ON)
option (ENABLE_SSE42 "Enable SSE 4.2 intrinsics." OFF)
option (ENABLE_FRAME_POINTER "Disable frame pointer omission" ON)
option (ENABLE_ASAN "Enable Address Sanitizer" OFF)
option (ENABLE_SSP "Enable Stack Smashing Protection" OFF)
option (ENABLE_STATIC_SNAPPY "Statically link against snappy" ON)
option (ENABLE_STATIC_LIBGCC "Statically link LD_PRELOAD wrappers against libgcc" ON)
option (ENABLE_STATIC_LIBSTDCXX "Statically link LD_PRELOAD wrappers against libstdc++" ON)
option (ENABLE_STATIC_EXE "Statically link executables" OFF)
option (BUILD_TESTING "Enable unit tests" OFF)
set (OUTPUT_DIRECTORY "/tmp/output" CACHE STRING "Directory where extracted blobs are written")

macro (add_compiler_flags)
    string (REPLACE ";" " " _FLAGS "${ARGV}")
    set (CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${_FLAGS}")
    set (CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${_FLAGS}")
endmacro ()

macro (add_linker_flags)
    string (REPLACE ";" " " _FLAGS "${ARGV}")
    set (CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} ${_FLAGS}")
    set (CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${_FLAGS}")
    set (CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${_FLAGS}")
endmacro ()

if (ENABLE_ASAN)
    add_compiler_flags (-fsanitize=address)
    add_linker_flags (-fsanitize=address)
endif ()

if (ENABLE_SSP)
    add_compiler_flags (-fstack-protector-all)
    add_linker_flags (-fstack-protector-all)
endif ()

include (CheckCXXSourceCompiles)
include (CheckCXXCompilerFlag)
include (TestBigEndian)

check_cxx_source_compiles ("__thread int i; int main() { return 0; }" HAVE_COMPILER_TLS)
if (NOT HAVE_COMPILER_TLS)
    message (FATAL_ERROR "C++ compiler does not support __thread keyword.")
endif ()

check_cxx_source_compiles ("#if defined(__i386__) || defined(__x86_64__)\nint main() { return 0; }\n#else\n#error\n#endif" HAVE_X86)

list (APPEND CMAKE_MODULE_PATH ${PROJECT_SOURCE_DIR}/cmake)
include (ConvenienceLibrary)
include (InstallPDB)

find_package (Python3 REQUIRED)
find_package (Threads REQUIRED)

if (ENABLE_X11)
    find_package (X11)
    if (X11_FOUND)
        include_directories (${X11_INCLUDE_DIR})
        add_definitions (-DHAVE_X11)
    endif ()
endif ()

set (CMAKE_C_STANDARD 99)
set (CMAKE_CXX_STANDARD 17)
set (CXX_STANDARD_REQUIRED ON)

add_definitions (
    -D__STDC_LIMIT_MACROS
    -D__STDC_FORMAT_MACROS
    -DOUTPUT_DIRECTORY="${OUTPUT_DIRECTORY}"
)

CHECK_CXX_COMPILER_FLAG("-fvisibility=hidden" CXX_COMPILER_FLAG_VISIBILITY)
if (CXX_COMPILER_FLAG_VISIBILITY)
    add_compiler_flags (-fvisibility=hidden)
endif ()

add_compiler_flags (-Wall -Wno-sign-compare -fno-strict-aliasing)
set (CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti -fno-exceptions")

if (HAVE_X86 AND ENABLE_SSE42)
    if (CMAKE_CXX_COMPILER_ID STREQUAL GNU)
        add_compiler_flags (-msse4.2)
    endif ()
    add_definitions (-DHAVE_SSE42)
endif ()

if (ENABLE_STATIC_EXE)
    set (CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -static-libgcc -static-libstdc++")
endif ()

if (ENABLE_FRAME_POINTER)
    add_compiler_flags (-fno-omit-frame-pointer)
endif ()

add_definitions (-D_GNU_SOURCE)

test_big_endian (HAVE_BIGENDIAN)
if (HAVE_BIGENDIAN)
    add_definitions (-DHAVE_BIGENDIAN)
endif ()

set (CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

enable_testing ()
if (CMAKE_CROSSCOMPILING AND NOT CMAKE_CROSSCOMPILING_EMULATOR)
    add_custom_target (check)
elseif (DEFINED CMAKE_BUILD_TYPE)
    add_custom_target (check COMMAND ${CMAKE_CTEST_COMMAND} --output-on-failure USES_TERMINAL)
else ()
    add_custom_target (check COMMAND ${CMAKE_CTEST_COMMAND} -C "$<CONFIG>" --output-on-failure USES_TERMINAL)
endif ()

execute_process(COMMAND dpkg-architecture -qDEB_HOST_MULTIARCH
    OUTPUT_VARIABLE ARCH_SUBDIR
    ERROR_QUIET
    OUTPUT_STRIP_TRAILING_WHITESPACE
)

set (DOC_DEFAULT_INSTALL_DIR share/doc/${CMAKE_PROJECT_NAME})
set (LIB_INSTALL_DIR lib${LIB_SUFFIX}/${CMAKE_PROJECT_NAME})
if (ARCH_SUBDIR)
    set (LIB_ARCH_INSTALL_DIR lib/${ARCH_SUBDIR}/${CMAKE_PROJECT_NAME})
else ()
    set (LIB_ARCH_INSTALL_DIR lib${LIB_SUFFIX}/${CMAKE_PROJECT_NAME})
endif ()
set (DOC_INSTALL_DIR "${DOC_DEFAULT_INSTALL_DIR}" CACHE STRING "Documentation installation directory")
set (SCRIPTS_INSTALL_DIR ${LIB_INSTALL_DIR}/scripts)
set (WRAPPER_INSTALL_DIR ${LIB_ARCH_INSTALL_DIR}/wrappers)

include_directories (${CMAKE_CURRENT_SOURCE_DIR}/compat)

if (NOT ENABLE_STATIC_EXE)
    if (NOT ENABLE_STATIC_SNAPPY)
        find_package (Snappy CONFIG)
    endif ()
    find_package (PNG REQUIRED)
    find_package (ZLIB 1.2.6)
    find_package (PkgConfig)
    if (PKG_CONFIG_FOUND)
        pkg_check_modules (BROTLIDEC IMPORTED_TARGET libbrotlidec>=1.0.7)
        pkg_check_modules (BROTLIENC IMPORTED_TARGET libbrotlienc>=1.0.7)
        pkg_check_modules (ZSTD IMPORTED_TARGET libzstd>=1.4.0)
    endif ()
endif ()

add_subdirectory (thirdparty)

include_directories (BEFORE ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/khronos)

include_directories (
    ${CMAKE_CURRENT_SOURCE_DIR}/lib/trace
    ${CMAKE_CURRENT_SOURCE_DIR}/lib/os
    ${CMAKE_CURRENT_SOURCE_DIR}/compat
)

add_subdirectory (lib)
add_subdirectory (dispatch)
add_subdirectory (helpers)
add_subdirectory (wrappers)

if (ENABLE_CLI)
    add_subdirectory (cli)
endif ()

find_package (Git)
add_custom_target (version
    BYPRODUCTS
        ${CMAKE_BINARY_DIR}/version.h
    COMMAND
        ${CMAKE_COMMAND} -D DST=${CMAKE_BINARY_DIR}/version.h
                         -D GIT_EXECUTABLE="${GIT_EXECUTABLE}"
                         -P ${CMAKE_SOURCE_DIR}/cmake/GenerateVersion.cmake
)

install (
    FILES LICENSE
    DESTINATION ${DOC_INSTALL_DIR}
    RENAME LICENSE.txt
)
