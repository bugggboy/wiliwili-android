//
// Android 老系统兼容层 —— 见 include/utils/android_compat.hpp
//
#include "utils/android_compat.hpp"

#if defined(__ANDROID__)

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <unwind.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// ===========================================================================
// 第一部分：补齐 libc 符号
//
// 背景（真机踩过的坑）：
//   libmpv.so / libav*.so 是 mpv-android 用较新 NDK 编的预编译件，内部按
//   HAVE_MEMFD_CREATE 走了一条 "直接引用 memfd_create" 的路径；我们自己静态
//   链进去的 OpenSSL 等又引用了 getentropy。
//   这两个符号在 API 23（Android 6.0）的 libc 里都没有：
//       memfd_create  -> API 30+
//       getentropy    -> API 28+
//   而它们在 ELF 里是 R_AARCH64_GLOB_DAT（取地址型重定位），**加载时就会解析**，
//   所以不是"用到才崩"，而是 dlopen 当场失败：
//       dlopen failed: cannot locate symbol "memfd_create" referenced by libavutil.so
//   Android 8+ 上系统恰好有这两个符号，所以新机一直正常，只有 6.0/7 会挂。
//
// 补一份实现即可让整包在 API 23 上自洽。这两份实现只在目标 API 低于系统
// 引入版本时才编译，高版本仍然用系统的。
// ===========================================================================
extern "C" {

#if !defined(__ANDROID_API__) || __ANDROID_API__ < 28
// 语义与 bionic 一致：最多 256 字节，来源是内核 CSPRNG。
int getentropy(void* buf, size_t buflen) {
    if (buf == nullptr || buflen > 256) {
        errno = EIO;
        return -1;
    }
    int fd = ::open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t done = 0;
    auto* out = static_cast<unsigned char*>(buf);
    while (done < buflen) {
        ssize_t n = ::read(fd, out + done, buflen - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return -1;
        }
        if (n == 0) break;
        done += static_cast<size_t>(n);
    }
    ::close(fd);
    if (done != buflen) {
        errno = EIO;
        return -1;
    }
    return 0;
}
#endif

#if !defined(__ANDROID_API__) || __ANDROID_API__ < 30
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

// 老内核（< 3.17）没有 memfd 系统调用，返回 -1/ENOSYS 交给调用方降级，
// 比"符号缺失导致整个进程加载不起来"好得多。
int memfd_create(const char* name, unsigned int flags) {
#if defined(SYS_memfd_create)
    long ret = ::syscall(SYS_memfd_create, name, flags);
    if (ret < 0) return -1;
    return static_cast<int>(ret);
#else
    (void)name;
    (void)flags;
    errno = ENOSYS;
    return -1;
#endif
}
#endif

}  // extern "C"

// ===========================================================================
// 第二部分：原生崩溃日志
//
// 目标：用户在没有电脑、不会 adb 的情况下也能把崩溃现场交回来。
// 信号处理函数里只能用异步信号安全的操作，所以这里全部用 write()，
// 不分配内存、不加锁；栈回溯用 _Unwind_Backtrace（不依赖 malloc）。
// ===========================================================================
namespace wiliwili {
namespace {

constexpr int MAX_FRAMES    = 40;
constexpr int ALT_STACK_SZ  = 64 * 1024;
constexpr const char* TAG   = "WILIWILI-CRASH";
constexpr const char* FILE_NAME = "crash-native.txt";

char altStack[ALT_STACK_SZ];
volatile sig_atomic_t inHandler = 0;

struct TraceState {
    void** cur;
    void** end;
};

_Unwind_Reason_Code traceCallback(_Unwind_Context* ctx, void* arg) {
    auto* st = static_cast<TraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc == 0) return _URC_NO_REASON;
    if (st->cur >= st->end) return _URC_END_OF_STACK;
    *st->cur++ = reinterpret_cast<void*>(pc);
    return _URC_NO_REASON;
}

void writeAll(int fd, const char* s, size_t n) {
    while (n > 0) {
        ssize_t w = ::write(fd, s, n);
        if (w <= 0) break;
        s += w;
        n -= static_cast<size_t>(w);
    }
}

// 同时写 logcat 和文件
void emit(int fd, const char* line) {
    __android_log_write(ANDROID_LOG_FATAL, TAG, line);
    if (fd >= 0) writeAll(fd, line, std::strlen(line));
}

const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default: return "SIG?";
    }
}

void crashHandler(int sig, siginfo_t* info, void* /*ctx*/) {
    if (inHandler) {
        // 处理过程中又崩了，别再进来，直接交给系统
        ::signal(sig, SIG_DFL);
        ::raise(sig);
        return;
    }
    inHandler = 1;

    int fd = -1;
    const char* dir = ::getenv("WILIWILI_CRASH_DIR");
    char path[512];
    if (dir != nullptr && dir[0] != '\0') {
        ::snprintf(path, sizeof(path), "%s/%s", dir, FILE_NAME);
        fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    }

    char line[512];
    ::snprintf(line, sizeof(line),
               "\n===== NATIVE CRASH %s(%d) addr=%p pid=%d tid=%d =====\n",
               signalName(sig), sig, info != nullptr ? info->si_addr : nullptr,
               static_cast<int>(::getpid()), static_cast<int>(::gettid()));
    emit(fd, line);

    void* frames[MAX_FRAMES];
    TraceState st{frames, frames + MAX_FRAMES};
    _Unwind_Backtrace(traceCallback, &st);

    int count = static_cast<int>(st.cur - frames);
    for (int i = 0; i < count; i++) {
        Dl_info dli{};
        if (::dladdr(frames[i], &dli) != 0 && dli.dli_fname != nullptr) {
            auto off = static_cast<unsigned long>(
                reinterpret_cast<uintptr_t>(frames[i]) -
                reinterpret_cast<uintptr_t>(dli.dli_fbase));
            const char* sym = dli.dli_sname != nullptr ? dli.dli_sname : "?";
            ::snprintf(line, sizeof(line), "  #%02d %s+0x%lx (%s)\n", i, dli.dli_fname, off, sym);
        } else {
            ::snprintf(line, sizeof(line), "  #%02d %p\n", i, frames[i]);
        }
        emit(fd, line);
    }
    emit(fd, "===== END =====\n");

    if (fd >= 0) {
        ::fsync(fd);
        ::close(fd);
    }

    // 还原默认动作再重发一次，让系统照常记录 tombstone / 走到 ANR 流程
    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

}  // namespace

void installAndroidCrashHandler() {
    stack_t ss{};
    ss.ss_sp    = altStack;
    ss.ss_size  = sizeof(altStack);
    ss.ss_flags = 0;
    ::sigaltstack(&ss, nullptr);

    struct sigaction sa {};
    sa.sa_sigaction = crashHandler;
    sa.sa_flags     = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    static const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
    for (int sig : kSignals) {
        ::sigaction(sig, &sa, nullptr);
    }
}

}  // namespace wiliwili

#endif  // __ANDROID__
