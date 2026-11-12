revision="v0.7"
url="https://github.com/termux/libandroid-shmem.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="others"
license="BSD-3-Clause"
extra_fuction() {
  # v3.4.9【x86_64 box64 符号断链根治】（用户 2026-11-12 会话日志实证：
  #   wine-9.0 x86_64 线启动即 "[BOX64] Error: Symbol __android_log_print
  #   not found, cannot apply R_X86_64_JUMP_SLOT … in …/lib/x86_64-bionic/
  #   libandroid-shmem.so" ×4，随后 wineboot 无输出挂死）：
  #   Termux shmem.c 的 DBG 宏走 __android_log_print（NDK liblog），
  #   box64 v0.4.4 的 wrapped 库集没有 liblog、宿主侧也解析不到该符号
  #   → x86_64 包内 libandroid-shmem.so 的 PLT 永久断链，任何 shm 调用
  #   即崩。潜伏缺陷（box64 形态重建后首次实跑暴露），非环境回归。
  #   修复（仅 x86_64 目标）：DBG 编译成空（日志本只进 logcat 用户不可
  #   见，零功能损失）+ 链接剥 -llog/-landroid（shmem.c 纯
  #   ioctl/socket/pthread，无任何 libandroid 符号引用）。
  #   aarch64 原生侧不受影响（bionic 宿主 liblog 解析正常，日志保留）。
  if [[ "$CC" == *x86_64* ]]; then
    sed -i \
      -e 's|#define DBG(...) __android_log_print(ANDROID_LOG_INFO, "shmem", __VA_ARGS__)|#define DBG(...)|' \
      shmem.c
    sed -i 's/ -llog -landroid$//' Makefile
    echo "[libandroid-shmem] x86_64: DBG 已置空 + 剥 -llog/-landroid（box64 无 liblog 符号断链根因）"
  fi
  make -j`nproc` || { echo "编译失败!" && exit 1;}
  DESTDIR="${destDir}" PREFIX="${prefix}" make install
}
