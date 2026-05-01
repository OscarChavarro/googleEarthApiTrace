##############################################################################
# API helpers


include_directories (
    ${CMAKE_BINARY_DIR}/dispatch
    ${CMAKE_SOURCE_DIR}/dispatch
)

add_convenience_library (glhelpers
    glfeatures.cpp
)
add_dependencies (glhelpers glproc)
