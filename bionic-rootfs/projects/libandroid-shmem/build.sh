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
  #   见，零功能损失）+ 链接剥 -llog。
  #   aarch64 原生侧不受影响（bionic 宿主 liblog 解析正常，日志保留）。
  #
  # v3.4.10【紧急修复：v3.4.9 把 -landroid 一并剥掉导致全构建线折在
  #   alsa-lib】（CI run 43147630509/43147662870 四线实证 + 本地 NDK r28b
  #   全量复现）：
  #   1) -landroid 绝不能剥 —— API>=26（本构建 API=28）时 shmem.c 的
  #      ashmem_create_region/ashmem_get_size_region 走
  #      ASharedMemory_create/ASharedMemory_getSize（libandroid.so）。
  #      v3.4.9 注释"shmem.c 纯 ioctl/socket/pthread，无任何 libandroid
  #      符号引用"判断失误。剥掉 -landroid 后 .so 带未解析符号照样能
  #      链出（共享库默认允许），但 Android lld 对最终目标默认
  #      --no-allow-shlib-undefined —— 任何链接 -landroid-shmem 的目标
  #      全挂：alsa-lib 是首个（pre_setup 往 LDFLAGS 加 -landroid-shmem），
  #      configure 编译器探活 conftest 即 "undefined reference:
  #      ASharedMemory_create (disallowed by --no-allow-shlib-undefined)"
  #      → "C compiler cannot create executables" → proton-10.0 /
  #      proton-9.0 × x86_64 / arm64ec 四线全灭。
  #      x86_64 运行时无碍：libandroid-stub 配方提供 x86_64 libandroid.so
  #      代理（platform-ns 转发真机平台库），ASharedMemory_* 正常承接。
  #   2) 架构判定改用 $targetArch —— 原判据 [[ "$CC" == *x86_64* ]] 会
  #      同时命中 aarch64 目标（CC 路径含 prebuilt/linux-x86_64/bin 工具链
  #      目录），把 aarch64 侧也误剥（CI 日志实证：aarch64 线也打印
  #      "x86_64: DBG 已置空"，且链接行同样没了 -llog -landroid）。
  #      aarch64 原生侧 bionic 宿主可解析 liblog/libandroid，无需处理。
  if [[ "${targetArch:-}" == "x86_64" ]]; then
    sed -i \
      -e 's|#define DBG(...) __android_log_print(ANDROID_LOG_INFO, "shmem", __VA_ARGS__)|#define DBG(...)|' \
      shmem.c
    # 仅剥 -llog（DBG 已置空，无 liblog 引用）；-landroid 保留（见上）
    sed -i 's/ -llog -landroid$/ -landroid/' Makefile
    echo "[libandroid-shmem] x86_64: DBG 已置空 + 剥 -llog（保留 -landroid：ASharedMemory_* 运行时由 libandroid-stub 代理承接）"
  fi
  make -j`nproc` || { echo "编译失败!" && exit 1;}
  DESTDIR="${destDir}" PREFIX="${prefix}" make install
}
