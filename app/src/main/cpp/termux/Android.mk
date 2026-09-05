LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# AnWind Termux 移植版原生库：
#   - termux.c        —— 上游 termux terminal-emulator 的 PTY/子进程管理（Apache-2.0）
#   - anwind_bridge.c —— AnWind 专有：FIFO 桥（shell → App 命令回传）
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c anwind_bridge.c

include $(BUILD_SHARED_LIBRARY)

# ============================================================
# anwind-reprefix：包安装后前缀重写工具（可执行）
# ============================================================
# 官方仓库 deb 与 bootstrap 把 "com.termux" 焊死在文件内容里
# （ELF .rodata / 脚本 shebang / tar 成员路径）。本工具做**等长
# 字节替换** com.termux → com.anwind（两者同为 10 字节，不改文件
# 长度与 ELF 偏移，文本与二进制通用），三种模式：
#   --file <path>   显式补丁单个文件
#   --tree <dir>    递归处理目录树（改名/内容/链接），输出改动数
#   （无参数）        按 dpkg info/*.list 增量补丁（stamp 机制）
#
# ⚠️ 命名技巧：这是可执行程序，但 Android APK 只打包 lib/<abi>/*.so，
# 故模块名以 lib 开头、以 .so 结尾；安装期由 TermuxBootstrapInstaller
# 从 nativeLibraryDir 拷贝到 $PREFIX/bin/anwind-reprefix 并 chmod 0700。
# ============================================================
include $(CLEAR_VARS)
LOCAL_MODULE:= libanwind_reprefix.so
LOCAL_SRC_FILES:= anwind_reprefix.c
LOCAL_CFLAGS:= -O2 -Wall -Wextra

include $(BUILD_EXECUTABLE)
