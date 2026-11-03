cmake_minimum_required(VERSION 3.22.1)
project(touchmux)

# Packaged as libtouchmux.so so AGP puts it in nativeLibraryDir;
# linked as a PIE with entry=main so Shizuku can execve it directly.
add_library(touchmux SHARED touchmux.c)
target_compile_options(touchmux PRIVATE -Wall -Wextra -O2 -fPIE)
target_link_options(touchmux PRIVATE -Wl,-e,main -pie)
target_link_libraries(touchmux log)
