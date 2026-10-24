cmake_minimum_required(VERSION 3.6)

# ============================================================================
# wiliwili Android 壳工程
#
# 本文件只是把仓库根目录的 wiliwili 工程拉进 Gradle 的 externalNativeBuild：
#   android/app/jni  →  上溯三级即 wiliwili 源码根
# borealis 的 toolchain.cmake 在 PLATFORM_ANDROID 下会：
#   - 打开 USE_SDL2 / USE_GLES2 / USE_LIBROMFS
#   - 由 library/CMakeLists.txt 自己 add_subdirectory(SDL)
# wiliwili 根 CMakeLists 用 program_target() 创建目标，
# 该宏在 PLATFORM_ANDROID 下会生成 SHARED 库（libwiliwili.so），
# 由 WiliwiliActivity.getLibraries() 加载。
# ============================================================================

get_filename_component(WILI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
message(STATUS "wiliwili source root: ${WILI_ROOT}")

set(BUILD_SHARED_LIBS ON CACHE BOOL "")
set(PLATFORM_ANDROID ON CACHE BOOL "")

# borealis 在 Android 上强制 USE_LIBROMFS，需要一个先在宿主机上编好的资源生成器。
# Windows 上产物是 libromfs-generator.exe，Linux/macOS 上没有后缀，两处都要认。
set(_romfs_gen_base "${WILI_ROOT}/library/borealis/libromfs-generator")
if (EXISTS "${_romfs_gen_base}.exe")
    set(LIBROMFS_PREBUILT_GENERATOR "${_romfs_gen_base}.exe" CACHE STRING "")
elseif (EXISTS "${_romfs_gen_base}")
    set(LIBROMFS_PREBUILT_GENERATOR "${_romfs_gen_base}" CACHE STRING "")
else()
    message(FATAL_ERROR
        "libromfs-generator 不存在：${_romfs_gen_base}[.exe]\n"
        "先在 library/borealis 下执行 build_libromfs_generator.sh（Windows 用 MINGW64 shell）")
endif()
message(STATUS "libromfs-generator: ${LIBROMFS_PREBUILT_GENERATOR}")

# mpv：Android 上是用预编译的 libmpv.so（按 ABI 分目录），
# mpv.pc 里只给 -lmpv，链接目录在这里按 ABI 指定。
# 必须在 add_subdirectory 之前调用，否则不会作用于随后创建的目标。
set(MPV_LIB_DIR "${WILI_ROOT}/.deps/mpv/lib/${CMAKE_ANDROID_ARCH_ABI}")
if (EXISTS "${MPV_LIB_DIR}/libmpv.so")
    message(STATUS "libmpv: ${MPV_LIB_DIR}/libmpv.so")
    link_directories("${MPV_LIB_DIR}")
else()
    message(WARNING "未找到 ${MPV_LIB_DIR}/libmpv.so，链接阶段会失败")
endif()

# cpr（wiliwili 的 HTTPS 客户端）需要一个 SSL 后端。
# Android NDK 不提供 OpenSSL 的头文件和库（系统里的 libssl 是私有 API），
# 所以由 CI 用 NDK 交叉编译一份静态 OpenSSL，按 ABI 放在 .deps/openssl/<abi>。
set(WILI_DEPS "$ENV{WILI_DEPS}")
set(OSSL_DIR "${WILI_DEPS}/openssl/${CMAKE_ANDROID_ARCH_ABI}")
if (EXISTS "${OSSL_DIR}/lib/libssl.a")
    set(OPENSSL_ROOT_DIR      "${OSSL_DIR}"                 CACHE PATH     "" FORCE)
    set(OPENSSL_INCLUDE_DIR   "${OSSL_DIR}/include"         CACHE PATH     "" FORCE)
    set(OPENSSL_SSL_LIBRARY   "${OSSL_DIR}/lib/libssl.a"    CACHE FILEPATH "" FORCE)
    set(OPENSSL_CRYPTO_LIBRARY "${OSSL_DIR}/lib/libcrypto.a" CACHE FILEPATH "" FORCE)
    list(APPEND CMAKE_PREFIX_PATH "${OSSL_DIR}")
    message(STATUS "OpenSSL: ${OSSL_DIR}")
else()
    message(FATAL_ERROR
        "未找到 ${OSSL_DIR}/lib/libssl.a。cpr 需要 SSL 后端，"
        "请先交叉编译 OpenSSL 并把 WILI_DEPS 指到 .deps")
endif()

# mpv_core.hpp 在 __SDL2__ 下写的是 #include <SDL2/SDL.h>，
# 这个路径是给「系统预装 SDL」用的；而本工程里 SDL 是 borealis 的子模块，
# 头文件是平铺的 ${SDL}/include/SDL.h。
# 造一个 shim 目录，使 SDL2/SDL.h 能解析到源码里的 SDL.h。
set(SDL_INC "${WILI_ROOT}/library/borealis/library/lib/extern/SDL/include")
if (NOT EXISTS "${SDL_INC}/SDL.h")
    message(FATAL_ERROR "找不到 SDL 头文件：${SDL_INC}/SDL.h（子模块没拉全？）")
endif()
set(SDL_SHIM_DIR "${CMAKE_CURRENT_BINARY_DIR}/sdl2_shim")
file(MAKE_DIRECTORY "${SDL_SHIM_DIR}")
if (NOT EXISTS "${SDL_SHIM_DIR}/SDL2")
    if (WIN32)
        # Windows 上创建符号链接需要开发者模式/管理员权限，直接复制目录更稳
        file(COPY "${SDL_INC}/" DESTINATION "${SDL_SHIM_DIR}/SDL2")
    else()
        file(CREATE_LINK "${SDL_INC}" "${SDL_SHIM_DIR}/SDL2" SYMBOLIC)
    endif()
endif()
include_directories("${SDL_SHIM_DIR}")
message(STATUS "SDL2 头文件映射: ${SDL_SHIM_DIR}/SDL2 -> ${SDL_INC}")

# ---------------------------------------------------------------
# SDL
# borealis 的 PLATFORM_ANDROID 分支只做 find_library(SDL2 NAMES SDL2 SDL2main)，
# 不做 add_subdirectory；纯源码构建时 find_library 找不到，链接阶段就会退化成
# 裸库名 `-lSDL2 -lSDL2main`。这里显式构建 SDL，并把它和桩库放进链接搜索路径。
# ---------------------------------------------------------------
set(SDL_DIR "${WILI_ROOT}/library/borealis/library/lib/extern/SDL")
if (NOT EXISTS "${SDL_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "找不到 SDL 源码：${SDL_DIR}")
endif()
set(SDL_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/SDL")
set(SDL_TEST_ENABLED_BY_DEFAULT OFF CACHE BOOL "" FORCE)
set(SDL_SHARED ON CACHE BOOL "" FORCE)
add_subdirectory("${SDL_DIR}" "${SDL_BUILD_DIR}")

# Android 上没有 SDL2main（那是桌面端提供 main() 包装的静态库），
# 但 borealis 无条件 -lSDL2main，补一个空静态库满足链接。
if (NOT TARGET SDL2main)
    set(STUB_DIR "${CMAKE_CURRENT_BINARY_DIR}/sdl2main_stub")
    file(MAKE_DIRECTORY "${STUB_DIR}")
    if (NOT EXISTS "${STUB_DIR}/empty.c")
        file(WRITE "${STUB_DIR}/empty.c" "")
    endif()
    add_library(SDL2main STATIC "${STUB_DIR}/empty.c")
    set_target_properties(SDL2main PROPERTIES
            OUTPUT_NAME "SDL2main"
            ARCHIVE_OUTPUT_DIRECTORY "${STUB_DIR}")
    message(STATUS "SDL2main 桩库: ${STUB_DIR}/libSDL2main.a")
endif()

link_directories("${SDL_BUILD_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/sdl2main_stub")

add_subdirectory(${WILI_ROOT} wiliwili_root)

# 链接用的是 -lSDL2（字符串，CMake 不知道依赖关系），这里显式补上构建顺序
if (TARGET SDL2 AND TARGET wiliwili)
    add_dependencies(wiliwili SDL2)
endif()
