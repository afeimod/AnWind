package com.winlator.cmod.x11;

import android.content.Context;

import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

/**
 * AnWind X11 显示组件（v2.23 集成核心）—— 替代 Winlator 的
 * XServerComponent + XServerView + GLRenderer 显示栈。
 *
 * 工作原理：
 * - exe（wine）进程的 DISPLAY 仍为 ":0"，其 libX11 按 TMPDIR 解析
 *   Unix socket（与 Winlator 原生行为一致，TMPDIR = imagefs/usr/tmp）；
 * - 本组件把 imagefs/usr/tmp/.X11-unix/X0 做成符号链接，指向 AnWind
 *   内置 X server（termux-x11/lorie，终端 `anwind-x11 :1` 或本集成
 *   自动拉起，socket 在 $PREFIX/tmp/.X11-unix/X<n>）——wine 的所有
 *   X 客户端连接由此无缝转发给 lorie；
 * - lorie 的渲染画面由 AnWind 桌面的 "X11 桌面" 浮动窗口（LorieView）
 *   显示 —— X11WindowController 收到 CmdEntryPoint 广播后自动开窗；
 * - 容器的 screenSize 经 [X11DisplayHost] 回调映射为 AnWind 的
 *   分辨率握手（X11ResolutionLink），X 屏幕按容器设置切换分辨率。
 *
 * 与 Winlator 原生显示的差异：
 * - 无 GLX/virgl 硬件 GL（lorie 不提供 GLX 扩展）：GL 游戏回落
 *   wined3d+llvmpipe（软件 GL）；
 * - Vulkan（DXVK/VKD3D + turnip）为主力 GPU 路径：Mesa WSI 在无 DRI3
 *   的 X server 上自动走 MIT-SHM/XP utImage 拷贝呈现
 *   （MESA_VK_WSI_DEBUG=sw），与 Termux 生态的 turnip+DXVK 方案一致。
 */
public class X11DisplayComponent extends EnvironmentComponent {
    /** lorie 默认显示号（anwind-x11 默认 :1）。 */
    public static final String DEFAULT_DISPLAY_NUMBER = "1";

    private final X11DisplayHost host;
    private File symlinkPath;
    private File symlinkPathFallback;
    /** v13：异步桥接线程停止标记（stop() 置位，防止会话结束后重复建桥）。 */
    private volatile boolean bridgeStopped = false;

    public X11DisplayComponent(X11DisplayHost host) {
        this.host = host;
    }

    @Override
    public void start() {
        Context context = environment.getContext();
        ImageFs imageFs = environment.getImageFs();
        bridgeStopped = false;

        // 1. 定位 AnWind X server（lorie）的 X11 socket
        String socketPath = X11SocketFinder.findSocket(context);
        if (socketPath == null) {
            // 2. 未运行 → 经宿主回调拉起（app_process 冷启动，内部轮询最长 8s）
            if (host != null && host.ensureX11Session()) {
                socketPath = X11SocketFinder.findSocket(context);
            }
        }
        // v12 加固：app_process 冷启动 + 广播时序波动下，宿主侧单轮轮询仍可能
        // 赶不上 socket 就位 —— 这里再做一轮短轮询（2s）兜底，避免整个会话黑屏。
        if (socketPath == null) {
            for (int i = 0; i < 4; i++) {
                try { Thread.sleep(500); }
                catch (InterruptedException e) { break; }
                socketPath = X11SocketFinder.findSocket(context);
                if (socketPath != null) {
                    android.util.Log.i("X11DisplayComponent", "X11 socket 延迟就位（第 " + (i + 1) + " 次重试）: " + socketPath);
                    break;
                }
            }
        }

        if (socketPath != null) {
            createSymlinks(imageFs, socketPath);
        }
        else {
            android.util.Log.w("X11DisplayComponent",
                "未发现 AnWind X11 socket（已查 " + java.util.Arrays.toString(X11SocketFinder.candidateDirs(context)) +
                "）—— 转入后台等待，socket 就位即建桥；" +
                "请先在 AnWind 终端执行 anwind-x11 :1 或在 Winlator 容器页重新启动");
            // v13：不再单次失败即放弃 —— wine 的 explorer/wineboot 连接 X 较晚
            // （冷启动可 >10s），后台继续等待并建桥，首次启动黑屏/不显示的
            // 竞态就此消除。wine 侧 DISPLAY=:0 不变，桥建成后新连接即生效。
            startBridgeWaiter(context, imageFs);
        }

        // 3. 容器分辨率 → AnWind 分辨率握手（X 屏幕切换 + 显示层拉伸铺满）
        if (host != null) host.applyContainerResolution();
    }

    /** v13：后台等待 X11 socket 就位并建桥（最长约 15s；stop() 时退出）。 */
    private void startBridgeWaiter(final Context context, final ImageFs imageFs) {
        Thread waiter = new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                if (bridgeStopped) return;
                try { Thread.sleep(500); }
                catch (InterruptedException e) { return; }
                String socketPath = X11SocketFinder.findSocket(context);
                if (socketPath != null) {
                    if (bridgeStopped) return;
                    android.util.Log.i("X11DisplayComponent",
                        "X11 socket 后台就位（第 " + (i + 1) + " 轮等待），建立显示桥: " + socketPath);
                    createSymlinks(imageFs, socketPath);
                    return;
                }
            }
            android.util.Log.w("X11DisplayComponent", "X11 socket 后台等待超时（约 15s），wine 本次无法显示画面");
        }, "X11BridgeWaiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void createSymlinks(ImageFs imageFs, String socketPath) {
        // 主路径：TMPDIR（imagefs/usr/tmp）—— winlator libX11 的解析基准
        File x11Dir = new File(imageFs.getTmpDir(), ".X11-unix");
        x11Dir.mkdirs();
        File target = new File(x11Dir, "X0");
        target.delete();
        linkOrCopy(socketPath, target);
        symlinkPath = target;

        // 兜底路径：imagefs/tmp（不同 libX11 补丁的另一种解析基准）
        File fallbackDir = new File(imageFs.getRootDir(), "tmp/.X11-unix");
        fallbackDir.mkdirs();
        File fallbackTarget = new File(fallbackDir, "X0");
        fallbackTarget.delete();
        linkOrCopy(socketPath, fallbackTarget);
        symlinkPathFallback = fallbackTarget;

        android.util.Log.i("X11DisplayComponent",
            "X11 显示桥已建立: " + target.getPath() + " → " + socketPath);
    }

    /** 建立 socket 链接；符号链接失败（个别 ROM 文件系统限制）时退化为拷贝 socket 节点。 */
    private static void linkOrCopy(String socketPath, File target) {
        try {
            com.winlator.cmod.core.FileUtils.symlink(socketPath, target.getPath());
        }
        catch (Throwable t) {
            android.util.Log.w("X11DisplayComponent", "symlink 异常: " + t);
        }
        if (!target.exists()) {
            android.util.Log.w("X11DisplayComponent", "symlink 失败，尝试拷贝 socket 节点");
            com.winlator.cmod.core.FileUtils.copy(new File(socketPath), target);
        }
    }

    @Override
    public void stop() {
        bridgeStopped = true;
        if (symlinkPath != null) symlinkPath.delete();
        if (symlinkPathFallback != null) symlinkPathFallback.delete();
        symlinkPath = null;
        symlinkPathFallback = null;
    }
}
