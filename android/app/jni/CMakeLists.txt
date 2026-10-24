cmake_minimum_required(VERSION 3.6)

# ============================================================================
# wiliwili Android 壳工程
#
# 本文件只是把仓库根目录的 wiliwili 工程拉进 Gradle 的 externalNativeBuild：
#   android/app/jni  →  上溯四级即 wiliwili 源码根
# borealis 的 toolchain.cmake 在 PLATFORM_ANDROID 下会：
#   - 打开 USE_SDL2 / USE_GLES2 / USE_LIBROMFS
#   - 由 library/CMakeLists.txt 自己 add_subdirectory(SDL)
# wiliwili 根 CMakeLists 用 program_target() 创建目标，
# 该宏在 PLATFORM_ANDROID 下会生成 SHARED 库（libwiliwili.so），
# 由 WiliwiliActivity.getLibraries() 加载。
# ============================================================================

get_filename_component(WILI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
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

add_subdirectory(${WILI_ROOT} wiliwili_root)
