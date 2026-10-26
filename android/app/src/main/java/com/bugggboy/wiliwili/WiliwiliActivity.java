package com.bugggboy.wiliwili;

import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

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

    /**
     * 视频专用 SurfaceView：mpv 用 vo=mediacodec_embed 直接渲染到这上面（零拷贝硬解），
     * 这样 4K 不用「硬解→拷回内存→上传 GL」，能和系统播放器一样流畅。
     * 它放在 SDLSurface 下方，SDLSurface 设透明背景让视频透出，弹幕/字幕/OSD 仍画在 GL 层。
     */
    private SurfaceView mVideoSurfaceView;

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

        // 创建视频专用 SurfaceView，放在 SDLSurface 下方
        setupVideoSurface();

        // 全屏沉浸式：隐藏状态栏与导航栏
        hideSystemUi();
    }

    /**
     * 覆写 SDLSurface 的创建：在它 addView 之前就设成 media overlay + 透明，
     * 这样 SurfaceFlinger 才会把它当作上层透明层和下层视频做 alpha 混合。
     * 在 onCreate 里对已 added 的 SurfaceView 调 setZOrderMediaOverlay 不生效
     * （surface 已创建，z-order 变更需要重建）。
     */
    @Override
    protected org.libsdl.app.SDLSurface createSDLSurface(android.content.Context context) {
        org.libsdl.app.SDLSurface s = super.createSDLSurface(context);
        try {
            s.setZOrderMediaOverlay(true);
            s.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        } catch (Throwable t) {
            Log.e("WILI-VIDEO", "setZOrderMediaOverlay failed", t);
        }
        return s;
    }

    /**
     * 创建视频渲染层。视频 SurfaceView 加在布局最底层（index 0），SDLSurface 用
     * media overlay 提到视频层之上并透明，让视频画面透出来。
     */
    private void setupVideoSurface() {
        try {
            ViewGroup layout = mLayout;
            if (layout == null) return;

            // window 背景必须透明，否则会盖住底层的视频 SurfaceView（SurfaceView 的
            // 挖洞机制只保证 content 透明，window 自带的背景仍会填充并遮挡）。
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));

            mVideoSurfaceView = new SurfaceView(this);
            mVideoSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    Log.i("WILI-VIDEO", "video surface created");
                    // 关键：固定 Surface buffer 尺寸为整屏物理像素。这样即使视频窗口
                    // 只占屏幕一部分（窗口布局变化），decoder 的输出 buffer 尺寸也不变，
                    // 由 SurfaceView 自己把整屏 buffer 缩放显示到窗口区域。
                    // 否则 Surface 尺寸一变（如 1200x675），mediacodec 的 decoder 输出会失效，
                    // 表现就是视频窗口黑屏。
                    try {
                        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                        holder.setFixedSize(dm.widthPixels, dm.heightPixels);
                        Log.i("WILI-VIDEO", "setFixedSize " + dm.widthPixels + "x" + dm.heightPixels);
                    } catch (Throwable t) {
                        Log.e("WILI-VIDEO", "setFixedSize failed", t);
                    }
                    onNativeVideoSurface(holder.getSurface());
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                    Log.i("WILI-VIDEO", "video surface changed: " + w + "x" + h);
                    // 不重复设 Surface：Surface 对象没变（buffer 尺寸被 setFixedSize 固定），
                    // 反复 DeleteGlobalRef/NewGlobalRef 会让 mpv 拿到的引用失效 → 黑屏。
                    // 窗口位置的变化由 native 通过 updateVideoSurfaceLayout 同步到 View 布局。
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    Log.i("WILI-VIDEO", "video surface destroyed");
                    onNativeVideoSurfaceDestroyed();
                }
            });
            // 视频层放最底层。注意：不能设为 INVISIBLE —— 那会销毁 Surface，导致 mpv
            // 失去渲染目标，视频不播放、无声音。非播放页由 SDLSurface 的不透明背景盖住它。
            layout.addView(mVideoSurfaceView, 0,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            // SDLSurface 的 z-order / 透明格式已在 createSDLSurface 里设置
        } catch (Throwable t) {
            Log.e("WILI-VIDEO", "setupVideoSurface failed", t);
        }
    }

    /**
     * 视频窗口位置/尺寸变化时，native 侧回调过来更新视频 SurfaceView 的布局。
     * Surface 的 buffer 尺寸被 setFixedSize 固定为整屏，View 尺寸缩到播放窗口，
     * SurfaceView 会把整屏 buffer 缩放显示到窗口区域。
     */
    public static void updateVideoSurfaceLayout(int x, int y, int width, int height) {
        WiliwiliActivity a = (WiliwiliActivity) mSingleton;
        if (a == null || a.mVideoSurfaceView == null) return;
        if (width <= 0 || height <= 0) return;
        final SurfaceView sv = a.mVideoSurfaceView;
        final int fx = x, fy = y, fw = width, fh = height;
        sv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup.LayoutParams lp = sv.getLayoutParams();
                    if (lp != null) {
                        lp.width  = fw;
                        lp.height = fh;
                        sv.setLayoutParams(lp);
                    }
                    sv.setX(fx);
                    sv.setY(fy);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 控制视频 SurfaceView 的可见性。只有在播放页（有视频窗口）时才显示，
     * 其他页面隐藏，避免首页等界面残留视频画面。
     */
    public static void setVideoSurfaceVisible(boolean visible) {
        WiliwiliActivity a = (WiliwiliActivity) mSingleton;
        if (a == null || a.mVideoSurfaceView == null) return;
        final SurfaceView sv = a.mVideoSurfaceView;
        final int vis = visible ? View.VISIBLE : View.INVISIBLE;
        if (sv.getVisibility() == vis) return;
        sv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sv.setVisibility(vis);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ---- 视频 Surface 的 native 接口（由 libwiliwili.so 实现） ----
    public static native void onNativeVideoSurface(Surface surface);
    public static native void onNativeVideoSurfaceDestroyed();


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
