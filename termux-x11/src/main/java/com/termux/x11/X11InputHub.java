package com.termux.x11;

import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;

import com.winlator.cmod.winhandler.WinHandler;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * AnWind（v2.22.3 fix10 → v2.23 winlator 集成重构）：X11 输入中枢（app 进程级单例）。
 *
 * 职责一：WinHandler 单例 —— wine 侧 winhandler.exe 的 UDP 7947 对端。
 *   随 app 进程存活、幂等启动，浮动窗口 / 全屏 Activity / Winlator 容器会话
 *   共享同一实例（XInput 手柄通道：AnWind 桌面虚拟手柄把按键经 WinHandler
 *   送进 wine 的 xinput1_3.dll，XInput API 游戏由此识别手柄）。
 *
 * 职责二：X11 直注桥 —— 当前活跃 LorieView 的按键/鼠标/滚轮直注 X server。
 *   桌面虚拟手柄（DInput 模式）、桌面快捷键都走本通道。
 *
 * v2.23 重构说明：移除 Winlator 移植残留在本类中的 InputControlsView
 * 手柄层与 profile 管理（旧 com.termux.x11.controller.* 已整体清除）。
 * WinHandler 类型改由 :winlator 模块（com.winlator.cmod，Winlator Cmod
 * 7.1.4x 引擎）提供 —— 该模块是本次重新集成的官方新版实现。
 *
 * 注意：本类不做任何触摸事件路由 —— 路由由各宿主完成
 * （全屏 Activity：MainActivity.dispatchTouchEvent；
 *   浮动窗口：X11Surface 的 SmartTouchBridge）。
 */
public class X11InputHub {
    private static final String TAG = "X11InputHub";

    private static volatile X11InputHub sInstance;

    public static X11InputHub get(Context context) {
        if (sInstance == null) {
            synchronized (X11InputHub.class) {
                if (sInstance == null)
                    sInstance = new X11InputHub(context.getApplicationContext());
            }
        }
        return sInstance;
    }

    private final Context appContext;
    private WinHandler winHandler;

    /**
     * 当前活跃的 X11 渲染视图（LorieView）。
     * 桌面虚拟手柄（GamepadController）的按键/鼠标经本引用直注 X ——
     * 手柄由此作用于 X11 界面（wine/游戏）。
     */
    private volatile LorieView activeLorieView;
    /** 已转发到 X 且仍按着的键（X11 窗口销毁时统一补发 UP，防卡键）。 */
    private final Set<Integer> forwardedDownKeys = new HashSet<>();

    private X11InputHub(Context appContext) {
        this.appContext = appContext;
    }

    /** appContext（供 Winlator 会话等获取 ApplicationContext）。 */
    public Context getAppContext() {
        return appContext;
    }

    /**
     * wine 侧游戏手柄通道（UDP 7947 对端），幂等启动。
     *
     * start() 内含 InetAddress.getLocalHost()（DNS 解析，属 StrictMode
     * 网络操作）与 DatagramSocket bind —— 必须切后台线程启动；调用方拿到
     * 的都是已创建的 WinHandler 实例，可以立刻 setWinHandler 接线，
     * UDP 收发在其自身线程就绪后自然生效。
     */
    public synchronized WinHandler getWinHandler() {
        if (winHandler == null) {
            WinHandler wh = new WinHandler();
            winHandler = wh;
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    wh.start();
                    Log.i(TAG, "WinHandler 已启动（UDP 7947，浮动窗口/全屏/Winlator 容器共享）");
                } catch (Throwable t) {
                    Log.e(TAG, "WinHandler 启动失败（不影响 X11 桌面其它功能）", t);
                }
            });
        }
        return winHandler;
    }

    // ============================================================
    // 桌面虚拟手柄 → X11 输入桥
    // ============================================================

    /**
     * X11 宿主注册/注销活跃渲染视图（X11Surface factory 创建时注册，
     * 窗口关闭时传 null）。注销时对仍按着的键补发 UP，防止游戏卡键。
     */
    public void setActiveLorieView(LorieView view) {
        LorieView old = activeLorieView;
        if (old != null && old != view) {
            synchronized (forwardedDownKeys) {
                for (int kc : forwardedDownKeys) {
                    try { old.sendKeyEvent(0, kc, false); } catch (Throwable ignored) {}
                }
                forwardedDownKeys.clear();
            }
        }
        activeLorieView = view;
    }

    /** X11 是否可接收手柄转发（活跃视图已连接 X server）。 */
    public boolean isX11ForwardAvailable() {
        LorieView v = activeLorieView;
        return v != null && LorieView.connected();
    }

    /** 手柄键盘事件直注 X。返回 false = 无 X11 目标（调用方回落原路径）。 */
    public boolean forwardKeyToX(int keyCode, boolean down) {
        LorieView v = activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendKeyEvent(0, keyCode, down);
            synchronized (forwardedDownKeys) {
                if (down) forwardedDownKeys.add(keyCode);
                else forwardedDownKeys.remove(keyCode);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手柄鼠标按键直注 X（在当前 X 指针位置按下/抬起）。 */
    public boolean forwardMouseButtonToX(int button, boolean down) {
        LorieView v = activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendMouseEvent(0f, 0f, button, down, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手柄滚轮直注 X（deltaY 正 = 内容下滚）。 */
    public boolean forwardWheelToX(float deltaY) {
        LorieView v = activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendMouseWheelEvent(0f, deltaY);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 静态便捷入口：桌面手柄（app 模块 GamepadController）调用。 */
    public static boolean forwardKey(int keyCode, boolean down) {
        X11InputHub h = sInstance;
        return h != null && h.forwardKeyToX(keyCode, down);
    }

    /**
     * 向 X 发送 Alt+Enter 组合键 —— wine/wined3d/DXVK 的全屏切换标准键。
     * 窗口化游戏一键切全屏：DXVK/wine 请求 ChangeDisplaySettings → X server
     * 经 RandR 把 X 屏幕切成游戏分辨率 → 显示层拉伸铺满 —— 黑边消失。
     */
    public static boolean sendAltEnter() {
        X11InputHub h = sInstance;
        LorieView v = h == null ? null : h.activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, true);
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ENTER, true);
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ENTER, false);
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendAltEnter failed", t);
            return false;
        }
    }

    public static boolean forwardMouseButton(int button, boolean down) {
        X11InputHub h = sInstance;
        return h != null && h.forwardMouseButtonToX(button, down);
    }

    public static boolean forwardWheel(float deltaY) {
        X11InputHub h = sInstance;
        return h != null && h.forwardWheelToX(deltaY);
    }

    public static boolean isX11ForwardReady() {
        X11InputHub h = sInstance;
        return h != null && h.isX11ForwardAvailable();
    }
}
