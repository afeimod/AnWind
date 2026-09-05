LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# AnWind Termux 移植版原生库：
#   - termux.c        —— 上游 termux terminal-emulator 的 PTY/子进程管理（Apache-2.0）
#   - anwind_bridge.c —— AnWind 专有：FIFO 桥（shell → App 命令回传）
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c anwind_bridge.c

include $(BUILD_SHARED_LIBRARY)
