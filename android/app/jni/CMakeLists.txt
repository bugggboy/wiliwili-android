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

# borealis 在 Android 上强制 USE_LIBROMFS，需要一个在 macOS/Linux 上先编好的资源生成器
set(LIBROMFS_PREBUILT_GENERATOR
    "${WILI_ROOT}/library/borealis/libromfs-generator"
    CACHE STRING "")
if (NOT EXISTS "${LIBROMFS_PREBUILT_GENERATOR}")
    message(FATAL_ERROR
        "libromfs-generator 不存在：${LIBROMFS_PREBUILT_GENERATOR}\n"
        "请先在 library/borealis 下执行 build_libromfs_generator.sh")
endif()

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
    file(CREATE_LINK "${SDL_INC}" "${SDL_SHIM_DIR}/SDL2" SYMBOLIC)
endif()
include_directories("${SDL_SHIM_DIR}")
message(STATUS "SDL2 头文件映射: ${SDL_SHIM_DIR}/SDL2 -> ${SDL_INC}")

add_subdirectory(${WILI_ROOT} wiliwili_root)
