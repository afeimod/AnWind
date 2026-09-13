cmake_minimum_required(VERSION 3.22.1)
project(winlator-engine)

# ============================================================
# AnWind :winlator 引擎模块原生库（精简子集）
# 原版 libwinlator.so 的组成里，与本集成相关的只有：
#   - xconnector_epoll.c   → XConnectorEpoll（SysVSHM/ALSA 服务事件循环）
#   - alsa_client.c        → ALSAClient（guest 侧音频流）
# drawable/gpu_image/vulkan/xr/patchelf 属 Winlator 自带 X server /
# XR / 内容补丁路径，本次集成不移植。
# ============================================================
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_library(winlator SHARED
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
)

target_link_libraries(winlator
        log
        android
        aaudio
)
