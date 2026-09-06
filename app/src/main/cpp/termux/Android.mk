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
# ⚠️ 构建方式：本工具是可执行程序，但 ndk-build 的 BUILD_EXECUTABLE
# 模块名含 ".so" 会触发 CXX1429（LOCAL_MODULE_FILENAME must not
# contain a file extension），故**不在此定义模块**，改由
# app/build.gradle.kts 的 buildReprefix* 任务直接调用 NDK clang
# 逐 ABI 编译为 libanwind_reprefix.so（命名技巧：Android APK 只打包
# lib/<abi>/*.so）。安装期由 TermuxBootstrapInstaller 从
# nativeLibraryDir 拷贝到 $PREFIX/bin/anwind-reprefix 并 chmod 0700。
# ============================================================
