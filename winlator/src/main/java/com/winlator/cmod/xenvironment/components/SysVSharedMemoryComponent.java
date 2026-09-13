package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.sysvshm.SysVSHMConnectionHandler;
import com.winlator.cmod.sysvshm.SysVSHMRequestHandler;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

/**
 * SysV 共享内存服务组件（AnWind 集成适配版）。
 *
 * 原版通过 xServer.setSHMSegmentManager() 把 shm 段注册进 Winlator 自带
 * X server（drawable 共享路径）。本集成改用 AnWind X11（termux-x11/lorie）
 * 显示端，无 Winlator X server，故移除该注册 —— 服务本身仍保留：guest 侧
 * 的 libandroid-sysvshm.so（LD_PRELOAD）依赖它分配 ashmem 段
 * （wine 的 ESYNC / Mesa WSI 的 MIT-SHM 均会用到）。
 */
public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;

    public SysVSharedMemoryComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }

        if (sysVSharedMemory != null) sysVSharedMemory.deleteAll();
    }
}
