revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"

# ============================================================
# AnWind box64（aarch64 · glibc 形态 · Winlator 模型）
# ------------------------------------------------------------
# v3.2.5 形态切换：bionic(-DANDROID=1, NDK clang) → glibc(aarch64
# 交叉 gcc + -DWINLATOR_GLIBC=1)。
#
# 动机（设备实测，v3.2.3 三份会话日志共同证据）：
#   * bionic 形态冒烟能过（box64 wine --version 正常输出），但真实
#     wine 负载（explorer ... wineboot -i 前缀构建）秒退 SIGSEGV(139)、
#     零输出 —— x86_64 wine（wine-9.2 / wine-proton 10.0）两种包同症，
#     冒烟路径与全量路径的行为差指向模拟器本体（bionic box64 在复杂
#     TLS/信号/自修改代码场景的成熟度远低于 glibc 形态）。
#   * Winlator / MiceWine 等成熟方案全部采用 glibc 形态 box64。
#
# glibc 形态的运行时前提（rootfs 侧由 glibc-aarch64 配方提供）：
#   * PT_INTERP → /data/data/com.anwind/files/rootfs/usr/lib/
#     aarch64-linux-gnu/ld-linux-aarch64.so.1（Android/bionic 没有
#     glibc 加载器 —— 本配方 install 阶段 patchelf 改写）
#   * DT_RPATH  → 同目录（libc.so.6/libm/libdl/libpthread/librt/
#     libgcc_s；用 --force-rpath 走 DT_RPATH 而非 DT_RUNPATH，
#     保证优先于外部注入的 LD_LIBRARY_PATH，bionic 目录零串扰）
#   * glibc-aarch64 配方产物随本包再带一份（lib/aarch64-linux-gnu/
#     进 box64 包 tar）—— anwind-container install-box64 免整包
#     重建 rootfs 即可完成升级。
#
# 构建工具链（宿主侧，host-deps.sh 已装）：
#   * aarch64-linux-gnu-gcc / g++（Ubuntu 交叉 GCC，glibc sysroot）
#   * patchelf（改写 PT_INTERP / DT_RPATH）
# fullCustomArgs 完全接管 cmake 参数 —— 绕开 NDK Android 工具链链
# （cmakeBaseArgs 的 android.toolchain.cmake 是 bionic 形态用的，
# 两者绝不混用；empty.sh 保证进入本项目时 CC/CFLAGS 环境干净）。
#
# cmake 选项（box64 v0.4.4 CMakeLists.txt 逐项核实存在）：
#   -DWINLATOR_GLIBC  Android 上的 glibc 环境宏（mmap 兼容 hack +
#                     dynacache 默认值，非 Termux 硬编码路径）
#   -DBAD_SIGNAL     si_info 不一致 workaround（原 -DBAD_SIGN 为
#                     笔误，box64 无此选项）
#   -DARM64/-DSD8xx  多 SoC 同开时 CMakeLists 首个分支（SD845）
#                     生效，armv8.2-a 通用档 —— 与 build-box64.yml
#                     （ARM 原生 runner 版）保持同一组参数
# 默认 v0.4.4：对齐 hangover-11.16 官方配套（hangover↔box64 的
# PE/wow64 协议版本敏感，错配可致 wineboot SEH 级联崩溃）。
# ============================================================

deps="glibc-aarch64"

# 设备侧绝对路径（rootfs 装机位置；与 anwind-wine / App 原生链路的
# rootfs 布局一致，build-box64.yml 独立构建版亦用同一路径）
GLIBC_LIB_DIR_DEVICE="/data/data/com.anwind/files/rootfs/usr/lib/aarch64-linux-gnu"
GLIBC_LOADER_DEVICE="${GLIBC_LIB_DIR_DEVICE}/ld-linux-aarch64.so.1"

fullCustomArgs="
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_SYSTEM_NAME=Linux
  -DCMAKE_SYSTEM_PROCESSOR=aarch64
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc
  -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++
  -DCMAKE_C_COMPILER_LAUNCHER=ccache
  -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
  -DARM64=ON
  -DARM_DYNAREC=ON
  -DBAD_SIGNAL=ON
  -DWINLATOR_GLIBC=ON
  -DSD845=ON
  -DSD865=ON
  -DSD888=ON
  -DSD8G2=ON
  -DNO_LIB_INSTALL=ON
  -DNO_CONF_INSTALL=ON
"

pre_setup() {
  # ---- 工具链硬校验（缺了 28 分钟后才死在编译深处，根因难查） ----
  if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
    echo "::error::缺少 aarch64-linux-gnu-gcc —— glibc 形态 box64 的交叉编译器"
    echo "   安装: sudo apt-get install -y gcc-aarch64-linux-gnu g++-aarch64-linux-gnu"
    echo "   （或重跑 bionic-rootfs/host-deps.sh —— v3.2.5 起已列入 debianPkgs）"
    return 1
  fi
  if ! command -v patchelf >/dev/null 2>&1; then
    echo "::error::缺少 patchelf —— box64 的 PT_INTERP/DT_RPATH 改写工具"
    echo "   安装: sudo apt-get install -y patchelf"
    echo "   （或重跑 bionic-rootfs/host-deps.sh —— v3.2.5 起已列入 debianPkgs）"
    return 1
  fi

  # ---- 依赖产物校验（glibc-aarch64 已按拓扑序先行构建并解压进 live prefix） ----
  if [[ ! -e "${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" ]]; then
    echo "::error::glibc-aarch64 依赖未就位: ${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 缺失"
    echo "   本配方 deps=glibc-aarch64，正常不会走到这里 —— 检查该配方是否构建失败"
    return 1
  fi
  echo "box64 glibc 运行库就位: ${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
  echo "构建形态: aarch64 glibc 交叉（aarch64-linux-gnu-gcc + WINLATOR_GLIBC/BAD_SIGNAL/ARM_DYNAREC）"
}

install() {
  local _bin="${destDir}${prefix}/local/bin/box64"
  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -p "${srcDir}/box64/build/box64" "${_bin}" || {
    echo "::error::box64 二进制缺失: ${srcDir}/box64/build/box64"
    return 1
  }

  # ---- PT_INTERP / DT_RPATH 改写（glibc 形态在 Android 上 exec 的硬前提） ----
  # 交叉 GCC 默认 PT_INTERP=/lib/ld-linux-aarch64.so.1 —— Android 上不
  # 存在，exec 直接 ENOENT（"not found"）。改写到 rootfs 内设备绝对路径。
  # RPATH 用 --force-rpath（DT_RPATH 优先于 LD_LIBRARY_PATH，防止 rootfs
  # bionic lib 目录通过环境变量串扰 glibc 符号解析）。
  echo "patchelf: PT_INTERP -> ${GLIBC_LOADER_DEVICE}"
  patchelf --set-interpreter "${GLIBC_LOADER_DEVICE}" "${_bin}" || {
    echo "::error::patchelf --set-interpreter 失败"; return 1; }
  echo "patchelf: DT_RPATH   -> ${GLIBC_LIB_DIR_DEVICE}"
  patchelf --force-rpath --set-rpath "${GLIBC_LIB_DIR_DEVICE}" "${_bin}" || {
    echo "::error::patchelf --set-rpath 失败"; return 1; }

  # ---- 自检：读回 PT_INTERP，确认改写生效 ----
  local _interp
  _interp="$(patchelf --print-interpreter "${_bin}" 2>/dev/null || true)"
  if [[ "${_interp}" != "${GLIBC_LOADER_DEVICE}" ]]; then
    echo "::error::PT_INTERP 改写未生效（读回: ${_interp:-空}）"
    return 1
  fi

  # ---- glibc 运行库随包自带（自包含升级：install-box64 无需重建 rootfs） ----
  # 从 live prefix 复制 glibc-aarch64 配方产物（拓扑序保证此刻已存在）；
  # rootfs 全量构建时 make-rootfs 会再合并一次同内容（幂等覆盖，无冲突）。
  mkdir -p "${destDir}${prefix}/lib/"
  cp -a "${prefix}/lib/aarch64-linux-gnu" "${destDir}${prefix}/lib/" || {
    echo "::error::glibc 运行库随包复制失败（源: ${prefix}/lib/aarch64-linux-gnu）"
    return 1
  }

  # ---- 产物信息（排障用） ----
  echo "box64 安装完成: ${_bin}"
  echo "  PT_INTERP: ${_interp}"
  echo "  随包 glibc 运行库: ${destDir}${prefix}/lib/aarch64-linux-gnu ($(ls "${destDir}${prefix}/lib/aarch64-linux-gnu" | wc -l) 项)"
  file "${_bin}" 2>/dev/null || true
}
