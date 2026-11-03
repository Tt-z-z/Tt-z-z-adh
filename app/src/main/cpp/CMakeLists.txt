cmake_minimum_required(VERSION 3.22.1)
project(touchmux)

# Real helper: PIE executable, named libtouchmux.so so it lands next to JNI libs
# after the Gradle copy task. Shizuku execve()'s this binary as shell.
add_executable(touchmux touchmux.c)
set_target_properties(touchmux PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    OUTPUT_NAME "touchmux"
)
target_compile_options(touchmux PRIVATE -Wall -Wextra -O2 -fPIE)
target_link_options(touchmux PRIVATE -pie)
target_link_libraries(touchmux log)

# Tiny SHARED stub so AGP always invokes CMake / packages an .so for the ABI.
add_library(touchmux_stub SHARED touchmux_stub.c)
target_link_libraries(touchmux_stub log)
