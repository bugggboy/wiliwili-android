# AGENTS.md — wiliwili Android 移植版开发指南

本仓库是 [xfangfang/wiliwili](https://github.com/xfangfang/wiliwili) 的 **Android 移植版**（GPL-3.0）。
上游支持 Switch / PSVita / PS4 / Windows / macOS / Linux，**Android 由本仓库独立维护**，
上游不接受 Android 相关补丁，因此 Android 的问题与改动都在这里处理。

改代码前请先读本文件；涉及上游业务逻辑时，优先查阅上游仓库与
[`AGENTS.md`](https://github.com/xfangfang/wiliwili/blob/yoga/AGENTS.md) 中更完整的约定。

---

## 平台约束（最重要）

| 项 | 值 | 说明 |
|---|---|---|
| minSdk | **23（Android 6.0）** | 决定了 NDK 只能用 r22/r23，见下表 |
| NDK | **22.1.7171670** | r22/r23 → api16+；r24/r25 → api19+；r26+ → 只到 api21+ **且低版本会跑不起来** |
| ABI | arm64-v8a、armeabi-v7a | 不提供 x86，因此标准模拟器跑不了 |
| 图形 | OpenGL ES 2.0 | `USE_GLES2`，走 SDL2 + GLES |
| 资源 | libromfs 强制开启 | 所有 `resources/` 编进 `libwiliwili.so` |
| 音频 | SDL2 音频回调 | 部分 ROM 的省电策略会杀线程，需要白名单 |

**改 NDK 版本前先确认 minSdk**：这两个是绑死的，升级 NDK 会直接掐掉低版本设备。

---

## 架构分层

| 层 | 目录 | 职责 |
|---|---|---|
| Activity | `wiliwili/include/activity/`、`wiliwili/source/activity/` | 顶层整屏页面 |
| Fragment | `wiliwili/include/fragment/`、`wiliwili/source/fragment/` | Activity 内的子页面 |
| Presenter | `wiliwili/include/presenter/` | 异步取数，Fragment 继承 Presenter |
| View | `wiliwili/include/view/` | 可复用组件（MPVCore、RecyclingGrid、VideoView…） |
| API | `wiliwili/include/api/bilibili/` | B 站接口封装（cpr + nlohmann/json） |
| Utils | `wiliwili/include/utils/` | Intent（导航）、ProgramConfig（配置）、ImageHelper 等 |

UI 框架是上游定制分支的 **borealis**（`library/borealis/`），所有视图继承 `brls::Box`。

---

## Android 壳层

```
android/
├── build.gradle / settings.gradle / gradle.properties
└── app/
    ├── build.gradle                  # minSdk 23 / NDK 22.1 / abiFilters
    ├── jni/CMakeLists.txt            # 把仓库根的 wiliwili 接进 externalNativeBuild
    └── src/main/
        ├── AndroidManifest.xml       # INTERNET 权限、GLES2、fullSensor
        └── java/com/bugggboy/wiliwili/WiliwiliActivity.java
```

`WiliwiliActivity` 继承 SDL 的 `SDLActivity`，三件事：
1. 注册 `BorealisHandler`（borealis 通过它调整屏幕亮度）
2. 全屏沉浸式
3. `onDestroy` 里 `System.exit(0)` —— borealis 大量使用静态变量，Activity 被重建会渲染异常

`getLibraries()` 返回 `{"SDL2", "wiliwili"}`，对应 `libSDL2.so` 与 `libwiliwili.so`。

**`org.libsdl.app.*` 的 Java 层不进仓库**，构建时从
`library/borealis/android-project/app/src/main/java/org` 复制过去，这样永远跟子模块同步。

---

## 关键约定

### Presenter 的异步安全

每个取数的 Fragment 都继承 Presenter，回调必须用宏保护：

```cpp
void requestSomething() {
    ASYNC_RETAIN
    bilibili::HTTP::getResultAsync<MyType>(url, params, [ASYNC_TOKEN](auto result) {
        ASYNC_RELEASE              // view 已销毁则提前返回
        brls::Threading::sync([this, result]() {
            // 这里可以安全更新 UI（主线程）
        });
    });
}
```

用 `CHECK_AND_SET_REQUEST` / `UNSET_REQUEST` 防止重复请求。

### XML 驱动的界面

布局在 `resources/xml/`。**改界面优先改 XML**，不要用代码堆布局。
资源经 libromfs 编进 `libwiliwili.so`，改了资源必须重新编译（不是热替换）。

### 触摸

上游输入模型是「焦点 + 按键」，Android 上由 SDL 把触摸映射成该模型。
新增可交互控件时确保它在焦点体系里可达（`setFocusable` / `registerXMLView`），否则点不到。

---

## 构建

CI：`.github/workflows/android.yml`，runner 是 `ubuntu-latest`（Android 不像 iOS 要 macOS，
一分钟档，跑一轮约 15 分钟，依赖有缓存）。

```bash
git clone --recursive -b yoga https://github.com/bugggboy/wiliwili-android.git
cd wiliwili-android

# 1. libromfs 生成器（不要用 borealis 自带的脚本，见下面坑 4）
cd library/borealis
cmake -S library/lib/extern/libromfs/generator -B build_libromfs_generator
cmake --build build_libromfs_generator -j"$(nproc)"
cp build_libromfs_generator/libromfs-generator .
cd ../..

# 2. 依赖（详见 workflow）：OpenSSL / libmpv.so / mpv 头文件 / mpv.pc

# 3. SDL Java 层
cp -r library/borealis/android-project/app/src/main/java/org android/app/src/main/java/

# 4. 构建
cd android && gradle :app:assembleDebug
```

### 四个必须记住的坑

1. **SDL 不会自动构建**
   borealis 的 `PLATFORM_ANDROID` 分支只有 `find_library(SDL2 NAMES SDL2 SDL2main)`，
   没有 `add_subdirectory`。`find_library` 失败后 `list(APPEND BRLS_PLATFORM_LIBS SDL2 SDL2main)`
   追加的是**字符串**，链接时变成 `-lSDL2 -lSDL2main`。
   → `jni/CMakeLists.txt` 里显式 `add_subdirectory(SDL)`、建空 `SDL2main` 静态库、
   `link_directories` 指到构建输出、再 `add_dependencies(wiliwili SDL2)` 保证顺序。

2. **`<SDL2/SDL.h>` 解析不到**
   `mpv_core.hpp` 在 `__SDL2__` 下 `#include <SDL2/SDL.h>`，那是给系统预装 SDL 用的；
   源码构建的头是平铺的 `SDL/include/SDL.h`。
   → 建 `build/.../sdl2_shim/SDL2 → SDL/include` 的符号链接并 `include_directories`。

3. **SSL：`No valid SSL backend found`**
   cpr 需要 SSL 后端，而 NDK **不提供** OpenSSL（系统 `libssl` 是私有 API，不能链）。
   → 用 NDK 交叉编译静态 OpenSSL，并在 `jni/CMakeLists.txt` 里设 `OPENSSL_ROOT_DIR` /
   `OPENSSL_INCLUDE_DIR` / `OPENSSL_SSL_LIBRARY` / `OPENSSL_CRYPTO_LIBRARY`（按 ABI 分目录）。
   OpenSSL 的 `Configure` 认 `ANDROID_NDK_ROOT`（不是 `ANDROID_NDK_HOME`），
   且要求 `<triple><api>-clang` 在 `PATH` 里，否则报 `no NDK aarch64-linux-android-gcc on $PATH`。

4. **`build_libromfs_generator.sh` 在 CMake 3.x 下不可用**
   它写的是 `cmake -B <build> <src>`，CMake 会把 borealis 根目录当源目录，
   接着 `include(toolchain.cmake)` 报 `Please set build target`。用带 `-S` 的等价命令。

### mpv

不用自己编译：从 [mpv-android](https://github.com/mpv-android/mpv-android) 的 release APK 里
解出 `lib/<abi>/libmpv.so`，头文件从 mpv 源码取（`include/mpv/*.h`）。
`mpv.pc` 只写 `-lmpv`，链接目录在 `jni/CMakeLists.txt` 里按 `${CMAKE_ANDROID_ARCH_ABI}` 指定
（pkg-config 无法区分 ABI）。

---

## 相对上游修改过的文件

改动集中在构建与打包层，业务代码保持与上游一致，便于日后合并上游更新：

| 文件 / 位置 | 改动 |
|---|---|
| `android/`（新增） | Gradle 工程 + Activity + Manifest |
| `README.md` | 改写为 Android 专用说明 |
| `AGENTS.md` | 本文件，改写为 Android 专用 |
| `.github/workflows/` | 仅保留 `android.yml`，其余平台 CI 已移除 |

wiliwili 根 `CMakeLists.txt` **未修改** —— `program_target()` 已经能处理 Android。

合上游更新时：`git remote add upstream https://github.com/xfangfang/wiliwili.git && git fetch upstream yoga`，
冲突通常只出现在 `README.md` / `AGENTS.md`。

---

## 验证清单

改动后至少确认：

- [ ] `android/app/build.gradle` 的 `minSdkVersion` 仍是 23，`ndkVersion` 仍是 22.1.7171670
- [ ] APK 里四个 `.so` 齐全：`libwiliwili.so`、`libSDL2.so`、`libmpv.so`、`libc++_shared.so`
- [ ] `libwiliwili.so` 里没有未解析的 OpenSSL 符号（`readelf -d` 无 `libssl.so` 依赖）
- [ ] 在 Android 6.0 与较新版本（如 Android 13）设备上都能启动
- [ ] 首页能出内容、能扫码登录、能播放并显示弹幕
