# Force certain components to be optimized as release
macro (target_optimize TARGET)
    target_compile_definitions (${TARGET} PRIVATE -DNDEBUG)
    if (MSVC)
        if (ENABLE_ASAN)
            target_compile_options (${TARGET} PRIVATE -wd5072)
        endif ()
    else ()
        target_compile_options (${TARGET} PRIVATE -O3)
    endif ()
endmacro ()

# Same as include command, but inside a function scope
function (include_with_scope)
    include (${ARGV})
endfunction ()

set (SUBMODULES_MISSING FALSE)

if (ENABLE_STATIC_SNAPPY OR NOT Snappy_FOUND)
    message (STATUS "Using bundled Snappy")
    include_with_scope (snappy.cmake)
endif ()

if (NOT ZLIB_FOUND)
    message (STATUS "Using bundled ZLIB")
    include_with_scope (zlib.cmake)
endif ()

if (NOT BROTLIDEC_FOUND OR NOT BROTLIENC_FOUND)
    message (STATUS "Using bundled Brotli")
    include_with_scope (brotli.cmake)
endif ()


# Seekable format (builds on top of system Zstd; sources are vendored locally)
add_convenience_library(zstd_seekable STATIC EXCLUDE_FROM_ALL
    zstd_seekable/seekable/zstdseek_compress.c
    zstd_seekable/seekable/zstdseek_decompress.c
    zstd_seekable/common/xxhash.c
)
target_include_directories(zstd_seekable PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/zstd_seekable/common
    ${CMAKE_CURRENT_SOURCE_DIR}/zstd_seekable/seekable
)
target_link_libraries (zstd_seekable PUBLIC
    PkgConfig::ZSTD
)

if (NOT ZSTD_FOUND)
    message (FATAL_ERROR "libzstd >= 1.4.0 was not found. Install libzstd-dev.")
else ()
    message (STATUS "Using system zstd")
endif ()

if(NOT MSVC)
    target_compile_options(zstd_seekable PRIVATE -Wno-uninitialized)
endif()

target_optimize(zstd_seekable)

if (CMAKE_EXECUTABLE_FORMAT STREQUAL "ELF")
    include_with_scope (libbacktrace.cmake)
endif ()

if (MSVC)
    add_subdirectory (getopt)
else ()
    add_library (getopt INTERFACE)
endif ()

add_subdirectory (crc32c)
