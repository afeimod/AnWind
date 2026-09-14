cmake_minimum_required(VERSION 3.22.1)
project(winlator-engine)

# ============================================================
# AnWind :winlator 引擎模块原生库（AnWind v8 修正）
# libwinlator.so 必须覆盖 Java 侧全部 native 符号：
#   - xconnector_epoll.c     → XConnectorEpoll / ClientSocket（事件循环）
#   - alsa_client.c          → ALSAClient（guest 侧音频流）
#   - sysvshared_memory.c    → SysVSharedMemory（SysVSHM/ALSA 共享内存）
#     （v8 修复：此前被错误精简，libwinlator.so 无 JNI 符号 →
#      ALSARequestHandler.createSharedMemory 一调即
#      UnsatisfiedLinkError 闪退，游戏启动必崩）
#   - vulkan.cpp             → GPUInformation（驱动/GPU 信息查询，
#     AdrenotoolsManager 驱动检测走此处；dlopen 系统库无需链接 vulkan）
# drawable/gpu_image/xr/patchelf 属 Winlator 自带 X server / XR /
# 内容补丁路径，本集成不使用，维持不移植。
# ============================================================
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")

add_library(winlator SHARED
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/sysvshared_memory.c
        winlator/vulkan.cpp
)

target_link_libraries(winlator
        log
        android
        aaudio
)
