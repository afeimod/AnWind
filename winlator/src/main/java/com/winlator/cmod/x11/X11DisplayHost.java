package com.winlator.cmod.x11;

/**
 * X11 显示组件宿主回调（由 :app 模块实现）。
 *
 * :winlator 引擎模块保持与 AnWind 桌面 UI 解耦：开窗、分辨率握手、
 * X server 拉起等涉及 AnWind 桌面基础设施（X11WindowController /
 * X11ResolutionLink / app_process 启动器）的能力经本接口注入。
 */
public interface X11DisplayHost {
    /**
     * 确保 AnWind X server（lorie）已运行。
     * 实现应：socket 已存在 → 直接返回 true；否则经 app_process 复刻
     * anwind-x11 启动 CmdEntryPoint 并等待 socket 出现（最长约 8s）。
     */
    boolean ensureX11Session();

    /** 容器启动后把容器分辨率映射为 AnWind 分辨率握手（X11ResolutionLink）。 */
    void applyContainerResolution();

    /** 打开/聚焦 AnWind 桌面的 "X11 桌面" 浮动窗口（LorieView 渲染）。 */
    void openDisplayWindow();
}
