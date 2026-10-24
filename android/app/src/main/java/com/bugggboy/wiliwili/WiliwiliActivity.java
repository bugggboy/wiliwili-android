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
        super.onCreate(savedInstanceState);

        // borealis 通过该 Handler 接收亮度调整请求
        PlatformUtils.borealisHandler = new BorealisHandler();

        // 全屏沉浸式：隐藏状态栏与导航栏
        hideSystemUi();
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
