package com.bugggboy.wiliwili;

import android.os.Bundle;
import android.view.View;

import org.libsdl.app.BorealisHandler;
import org.libsdl.app.PlatformUtils;
import org.libsdl.app.SDLActivity;

/**
 * wiliwili 的 Android 入口。
 *
 * 真正的界面由 C++ 侧（borealis + wiliwili）绘制在 SDL 提供的 GL 表面上，
 * 这里只负责三件事：
 *   1. 交给 SDLActivity 创建 Surface / 加载 .so
 *   2. 注册 BorealisHandler，让 borealis 能调整屏幕亮度
 *   3. 退出时强制结束进程（borealis 大量使用静态变量，重入会出问题）
 */
public class WiliwiliActivity extends SDLActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate() 之前设置：SDLActivity 在 onCreate 里就起原生线程了。
        // wiliwili 的 ProgramConfig 用 getenv("HOME") 推导配置目录，而 Android 默认不设 HOME，
        // 不设的话会走到不可访问的路径，一读就 filesystem_error → SIGABRT。
        try {
            android.system.Os.setenv("HOME", getFilesDir().getParentFile().getAbsolutePath(), true);
        } catch (Throwable ignored) {
            // Os.setenv 在极老系统上可能不可用，退化为默认行为
        }
        installCaBundle();
        setupCrashDir();
        installJavaCrashHandler();
        super.onCreate(savedInstanceState);

        // borealis 通过该 Handler 接收亮度调整请求
        PlatformUtils.borealisHandler = new BorealisHandler();

        // 全屏沉浸式：隐藏状态栏与导航栏
        hideSystemUi();
    }

    /**
     * Android 没有 OpenSSL 默认查找的 CA bundle，各 ROM 的系统证书集也不全（有的还带代理软件
     * 自己的根证书），导致 curl 报 "unable to get local issuer certificate"。
     * 这里随包带一份完整的 Mozilla 根证书集，拷到私有目录后通过环境变量交给 curl。
     */
    private void installCaBundle() {
        try {
            java.io.File target = new java.io.File(getFilesDir(), "cacert.pem");
            if (!target.exists() || target.length() < 1024) {
                java.io.InputStream in = getAssets().open("cacert.pem");
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
            }
            android.system.Os.setenv("WILIWILI_CA_BUNDLE", target.getAbsolutePath(), true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给原生层的崩溃处理器指定落盘目录。
     * 优先用外部私有目录（Android/data/<pkg>/files，连电脑或用文件管理器都能直接取，
     * 不需要任何权限），拿不到时退回内部私有目录。
     */
    private void setupCrashDir() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            android.system.Os.setenv("WILIWILI_CRASH_DIR", dir.getAbsolutePath(), true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 原生崩溃有 crash-native.txt，Java 侧的异常（SDL 初始化、权限、OOM 等）另存一份，
     * 两边都覆盖到，用户只需把这两个文件发回来就能定位。
     */
    private void installJavaCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    java.io.PrintWriter writer = new java.io.PrintWriter(
                            new java.io.FileWriter(new java.io.File(dir, "crash-java.txt"), true));
                    writer.println("===== JAVA CRASH thread=" + thread.getName() + " =====");
                    e.printStackTrace(writer);
                    writer.println("===== END =====");
                    writer.flush();
                    writer.close();
                } catch (Throwable ignored) {
                }
                android.util.Log.e("WILIWILI-CRASH", "java crash", e);
                if (previous != null) previous.uncaughtException(thread, e);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Android 不推荐 exit(0)，但 borealis 依赖大量静态状态，
        // 从后台恢复时若 Activity 被重建会出现渲染异常，这里强制退出。
        System.exit(0);
    }

    /**
     * SDL 会按顺序加载这些库。
     * "wiliwili" 对应 CMake 里的目标名（libwiliwili.so）。
     */
    @Override
    protected String[] getLibraries() {
        return new String[] {
                "SDL2",
                "wiliwili"
        };
    }
}
