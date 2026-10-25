//
// Android 老系统兼容层（仅 __ANDROID__ 下编译，桌面平台不受影响）
//
// 提供两样东西：
//   1) 系统 libc 符号补齐：getentropy / memfd_create 在低版本 Android 上不存在，
//      而预编译的 libmpv/libav* 会引用它们，导致 dlopen 直接失败。
//   2) 原生崩溃日志：SIGSEGV/SIGABRT 等信号触发时把栈写进 logcat 和私有目录文件，
//      方便在没有电脑抓 logcat 的情况下定位闪退。
//
#pragma once

#if defined(__ANDROID__)

namespace wiliwili {

/**
 * 安装原生崩溃处理器。
 * 崩溃信息会以 tag "WILIWILI-CRASH" 打到 logcat，同时追加写入
 * $WILIWILI_CRASH_DIR/crash-native.txt（目录由 Java 层设置）。
 */
void installAndroidCrashHandler();

}  // namespace wiliwili

#endif
