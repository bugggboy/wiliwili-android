<p align="center">
    <img src="resources/svg/cn.xfangfang.wiliwili.svg" alt="logo" height="120" width="120"/>
</p>

<h1 align="center">wiliwili for Android</h1>

<p align="center">
  B 站第三方客户端的 <b>Android 移植版</b> · 支持 Android 6.0 及以上
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="ABI" src="https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-blue">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-23-lightgrey">
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0-green">
</p>

<p align="center">
  <a href="#关于本项目"><b>关于本项目</b></a> |
  <a href="#功能"><b>功能</b></a> |
  <a href="#安装"><b>安装</b></a> |
  <a href="#已知限制"><b>已知限制</b></a> |
  <a href="#从源码构建"><b>从源码构建</b></a> |
  <a href="#致谢"><b>致谢</b></a>
</p>

---

## 关于本项目

> **这是 [xfangfang/wiliwili](https://github.com/xfangfang/wiliwili) 的非官方 Android 移植版。**
>
> 本仓库的代码来自原作者 **xfangfang**，我在此基础上做了 Android 平台适配与二次开发。
> 它不是上游项目的一部分，上游**不支持、也不接受** Android 移植（上游维护者已明确表示
> Android / iOS 不在讨论范围内），因此 **Android 相关的问题请提到本仓库，不要去打扰原作者**。

| | |
|---|---|
| **原作者** | xfangfang |
| **作者主页** | https://github.com/xfangfang |
| **上游仓库** | https://github.com/xfangfang/wiliwili |
| **上游支持平台** | Nintendo Switch / PSVita / PS4 / Windows / macOS / Linux |
| **本仓库** | https://github.com/bugggboy/wiliwili-android （Android 移植） |
| **同系列** | [wiliwili for iOS](https://github.com/bugggboy/wiliwili) |
| **许可证** | GPL-3.0（与原项目一致，本仓库同样以 GPL-3.0 分发） |

**如果你觉得这个客户端做得好，功劳属于原作者，请先去上游仓库点一个 Star。**
本项目只做了让它能在 Android 上编译和运行的工作，界面、交互逻辑、B 站接口封装全部来自上游。

### 本移植版相对上游的改动

1. **构建系统**：新增 `android/` Gradle 工程与 `android/app/jni/CMakeLists.txt`，
   以 `PLATFORM_ANDROID=ON` 把仓库根的 wiliwili 工程接入 `externalNativeBuild`。
   上游的 CMake 已经用 `program_target()` 创建目标，该宏在 Android 下会自动生成
   `SHARED` 库（`libwiliwili.so`），因此**业务代码与根 CMakeLists 未作修改**。
2. **SDL 处理**：borealis 的 `PLATFORM_ANDROID` 分支只用 `find_library()` 查找系统 SDL2，
   纯源码构建时会退化成裸库名 `-lSDL2 -lSDL2main`。本移植版显式 `add_subdirectory(SDL)`，
   并为 Android 补了一个空的 `SDL2main` 桩库（该库是桌面端提供 `main()` 包装用的）。
3. **头文件映射**：`mpv_core.hpp` 在 `__SDL2__` 下写的是 `#include <SDL2/SDL.h>`（针对系统预装 SDL），
   而源码构建的 SDL 头文件是平铺的，因此加了一个 `SDL2/ → SDL/include/` 的目录映射。
4. **SSL 后端**：cpr 需要 SSL，而 Android NDK 不提供 OpenSSL 的头文件与库
   （系统里的 `libssl` 是私有 API），因此由构建脚本用 NDK 交叉编译一份静态 OpenSSL。
5. **播放核心**：使用 [mpv-android](https://github.com/mpv-android/mpv-android) 提供的
   arm64-v8a 与 armeabi-v7a 预编译 `libmpv.so`，避免在 CI 里交叉编译 FFmpeg + mpv。

除以上改动外，应用层业务代码未做修改。

---

## 功能

界面与操作逻辑与上游保持一致，针对触摸屏做了适配。

- **首页**：推荐 / 热门 / 番剧 / 影视 / 直播 等分区横向滑切
- **搜索**：热搜、视频、番剧、影视，支持筛选
- **动态**：关注 UP 主的最新投稿
- **直播**：关注的主播与系统推荐
- **播放页**：视频、番剧、电影、纪录片、综艺，支持**弹幕**与评论
- **个人页**：扫码登录、历史记录、我的收藏、我的番剧、我的追剧
- **主题**：深色 / 浅色主题，跟随系统自动切换
- **多语言**：简体中文、繁体中文、日语、韩语、英语

触屏操作：单指点击选择、上下滑动翻页、左右滑动切分区、播放页点击呼出控制栏。
支持外接蓝牙 / USB 手柄（borealis 的输入层与上游一致）。

---

## 安装

**直接安装 APK**，无需 root、无需 Magisk。

前往 [Releases](https://github.com/bugggboy/wiliwili-android/releases/tag/android-preview)
下载 `wiliwili-android-debug.apk`，用文件管理器或 `adb install` 安装即可。

```bash
adb install -r wiliwili-android-debug.apk
```

**系统要求**

- Android **6.0（API 23）** 及以上
- ABI：`arm64-v8a` 或 `armeabi-v7a`（暂不提供 x86 模拟器版本）
- OpenGL ES 2.0 及以上

> 目前构建产物是 **debug 签名**，可以直接安装，但每次换包都需要卸载重装或接受
> 「签名不一致」提示。后续会提供固定密钥签名的 release 包。

**登录**：个人页点击「扫码登录」，用手机上的 B 站 App 扫描屏幕上的二维码。

---

## 已知限制

- **未做全机型回归**：目前验证的是构建链路与启动，不同厂商 ROM 的渲染 / 音频行为可能有差异
- 极少数编码格式可能无画面（mpv 的解码后端以硬解为主，可尝试切换渲染方式）
- 暂不提供 x86 / x86_64 ABI，因此不能在标准 Android 模拟器里跑
- 未适配 Android 分屏 / 画中画，退出后不保留后台播放
- 部分深度定制 ROM 可能因省电策略杀掉音频线程，需要手动加白名单

---

## 技术栈

| 层 | 组件 | 说明 |
|---|---|---|
| UI 框架 | [borealis](https://github.com/xfangfang/borealis)（上游定制分支） | nanovg 绘制，Android 后端走 SDL2 + OpenGL ES 2.0 |
| 窗口 / 输入 | SDL 2.28（borealis 子模块） | 触摸与手柄事件映射到上游的输入模型 |
| 播放核心 | [mpv](https://github.com/mpv-player/mpv) 0.40（[mpv-android](https://github.com/mpv-android/mpv-android) 预编译） | `libmpv.so`，动态链接 |
| 解码 | FFmpeg（已静态链进 libmpv.so） | — |
| 网络 | **OpenSSL 1.1.1w**（NDK 交叉编译的静态库） | cpr 的 HTTPS 后端 |
| 资源 | libromfs | 所有资源编进 `libwiliwili.so` |
| 工具链 | NDK **22.1.7171670** / CMake 3.22.1 / AGP 7.4.2 / Gradle 7.6.4 | NDK 版本与 minSdk 强绑定，见下 |

**为什么锁 NDK 22**：NDK 版本决定了能支持的最低 API —— r22/r23 支持 api16+，
r24/r25 支持 api19+，r26 及以上只支持 api21+ 且会让低版本 Android 跑不起来。
本项目要求 Android 6.0，因此必须用 r22。

**构建产物指标**

| 项 | 值 |
|---|---|
| minSdk / targetSdk / compileSdk | 23 / 31 / 31 |
| ABI | arm64-v8a、armeabi-v7a |
| 打包内容 | `libwiliwili.so`（业务 + borealis + 资源）、`libSDL2.so`、`libmpv.so`、`libc++_shared.so` |
| APK 大小 | 约 105 MB（debug，未压缩优化，含两个 ABI） |

---

## 从源码构建

### 方式一：GitHub Actions（推荐，仓库已配好）

推送代码到 `yoga` 分支或手动触发 `.github/workflows/android.yml`，流程为：

```
ubuntu-latest
  ├─ 安装 NDK 22.1 + CMake 3.22
  ├─ 构建 host 版 libromfs-generator
  ├─ 交叉编译 OpenSSL（arm64-v8a / armeabi-v7a）
  ├─ 从 mpv-android 的 release APK 提取 libmpv.so
  ├─ 复制 SDL 的 Java 层到 android/app/src/main/java/org
  └─ gradle :app:assembleDebug → APK
```

### 方式二：本地

```bash
git clone --recursive -b yoga https://github.com/bugggboy/wiliwili-android.git
cd wiliwili-android

# 1. libromfs 生成器（Android 强制 USE_LIBROMFS）
cd library/borealis
cmake -S library/lib/extern/libromfs/generator -B build_libromfs_generator
cmake --build build_libromfs_generator -j
cp build_libromfs_generator/libromfs-generator .
cd ../..

# 2. 依赖：OpenSSL（NDK 交叉编译）+ libmpv.so + mpv 头文件 + mpv.pc
#    具体命令见 .github/workflows/android.yml 的对应步骤

# 3. SDL 的 Java 层
cp -r library/borealis/android-project/app/src/main/java/org android/app/src/main/java/

# 4. 构建
cd android
./gradlew assembleDebug    # 或 gradle :app:assembleDebug
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

### 四个必须记住的坑

1. **SDL 不会被自动构建**
   borealis 的 Android 分支只 `find_library(SDL2 ...)`，不 `add_subdirectory`；
   `find_library` 失败后 `list(APPEND ... SDL2 SDL2main)` 追加的是字符串，链接时变成
   `-lSDL2 -lSDL2main`。必须自己 `add_subdirectory(SDL)` + 提供 `SDL2main` 桩库 + `link_directories`。
2. **`<SDL2/SDL.h>` 找不到**
   源码构建的 SDL 头文件是平铺的（`SDL/include/SDL.h`），需要建 `SDL2/` 目录映射。
3. **cpr 报 `No valid SSL backend found`**
   NDK 不提供 OpenSSL，必须自己交叉编译。而且 OpenSSL 的 `Configure` 认
   `ANDROID_NDK_ROOT`（不是 `ANDROID_NDK_HOME`），并要求 `<triple><api>-clang` 在 `PATH` 里。
4. **`build_libromfs_generator.sh` 不能用**
   它写的是 `cmake -B <build> <src>`，CMake 3.x 会把 borealis 根目录当成源目录，
   然后报 `Please set build target`。改用带 `-S` 的等价命令。

---

## 致谢

- **[xfangfang](https://github.com/xfangfang)** —— wiliwili 的原作者。整个应用的设计、
  界面、业务逻辑与 B 站接口封装都出自他手，本项目只是把它搬到了 Android 上。
- **[borealis](https://github.com/xfangfang/borealis)** —— 上游自研的跨平台 UI 框架。
  它的 `android-project/` 模板与 `PLATFORM_ANDROID` 平台层是这次移植得以成立的前提。
- **[mpv](https://github.com/mpv-player/mpv) / [FFmpeg](https://ffmpeg.org/)** —— 播放与解码。
- **[mpv-android](https://github.com/mpv-android/mpv-android)** —— 提供 arm64 / armv7 的 `libmpv.so`。
- **[SDL](https://github.com/libsdl-org/SDL)** —— Android 上的窗口、输入与音频。
- **[OpenSSL](https://www.openssl.org/)** —— HTTPS。
- 以及所有上游依赖库的作者。

---

## 免责声明

本项目为**第三方非官方客户端**，与哔哩哔哩（bilibili）官方无任何关联，
仅供个人学习与交流使用。所有视频内容与版权归原平台及创作者所有。
请勿将本项目用于商业用途。

---

## 许可证

[GPL-3.0](./LICENSE)，与上游一致。

根据 GPL-3.0 的要求：本仓库是对原项目的修改版本，修改内容已在上文
「本移植版相对上游的改动」中说明；原始版权声明与许可证文件均完整保留。
任何分发本移植版的行为同样须遵循 GPL-3.0，并提供完整源码。
