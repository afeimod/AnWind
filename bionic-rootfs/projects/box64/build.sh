revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"

# ============================================================
# AnWind bionic-box64（aarch64 / glibc 链接 / WINLATOR_GLIBC）
# ------------------------------------------------------------
# 桌面 wine 容器的 x86_64 仿真核心（/usr/local/bin/box64，
# aarch64 设备上运行 bionic 链接的 x86_64 wine —— wine 的 unix
# 侧 ELF 由 box64 内部加载器全仿真执行，宿主侧 libc 形态与仿真
# 侧无关，见 box64 源码 src/elfs/elfloader.c 的 hasElfInterp：
# 只检测 PT_INTERP 存在性、从不执行被仿真程序的解释器）。
#
# 【为什么是 glibc 链接而不是 NDK/bionic 链接】（v3.2.5 重做）
# 旧配方 -DANDROID=ON + NDK clang 产出 bionic 链接的 box64，
# 设备实测（日志 wine-9.2.2 / wine-10.0 / wine-9.2-1）：冒烟
# `box64 wine --version` 能过，但 `box64 wine ... wineboot -i`
# 毫无输出直接 SIGSEGV(139)，wineboot 前缀建不出来。改用与终端
# box64（.github/workflows/build-box64.yml，用户实证可跑）完全
# 同款的 WINLATOR_GLIBC 模型：box64 以 glibc 二进制形态构建，
# 运行期由 rootfs 私有 glibc 加载器（ld-linux-aarch64.so.1，
# 由依赖 glibc-aarch64 配方安装进
# /data/data/com.anwind/files/rootfs/usr/lib/aarch64-linux-gnu/）
# 直接拉起 —— 与 Winlator/终端 glibc 同一运行模型，wine 相关
# 的 mmap 修正（wrappedlibc.c 的 WINLATOR_GLIBC 分支）同步生效。
#
# 【加载器钉死】构建完成后 patchelf：
#   --set-interpreter /data/data/com.anwind/files/rootfs/usr/lib/
#     aarch64-linux-gnu/ld-linux-aarch64.so.1
#   --force-rpath --set-rpath <同目录>
# （与终端 start-tfm / build-box64.yml 的 patchelf 手法一致；
#   DT_RPATH 而非 RUNPATH：优先级高于会话注入的 LD_LIBRARY_PATH，
#   避免 bionic 库目录串进来）。CI 宿主与设备共用同一绝对前缀
# （default.conf 的 prefix），构建期校验的路径即设备运行期路径。
#
# 【工具链】ubuntu-24.04 CI 宿主上用 aarch64-linux-gnu-gcc 交叉
# 构建（host-deps.sh 安装；glibc 2.39 与 noble 同源、与
# glibc-aarch64 配方解包的运行库 ABI 严格一致）。aarch64 宿主
# 本地构建场景自动改用原生 gcc。
#
# 【为什么不再用 NDK 工具链】构建系统对 cmake 项目的默认参数
# （base-configure-args.conf 的 cmakeBaseArgs）注入 NDK Android
# toolchain file，且"others"类项目（glibc-aarch64）的 load_env
# 会向本进程导出 NDK 的 CC/ASM/CFLAGS（含 --sysroot）并把 NDK
# bin 前置到 PATH —— CMake 的 ASM 探测曾因此选中 NDK 里仅支持
# x86 的 yasm，arm64_prolog.S 直接 "yasm: FATAL: unrecognized
# object format unwind-tables"（CI 实测，v3.2.4 报废根因）。
# 故本配方使用 fullCustomArgs 完整接管 cmake 参数（绕过 NDK
# toolchain file），显式钉死 C/ASM 编译器为交叉 gcc，并在
# pre_setup 里清掉上游残留的 NDK 编译环境变量。
# ============================================================

# glibc 运行库目录（构建期 = prefix 下；设备期同一绝对路径）
GLIBC_LIBDIR="${prefix}/lib/aarch64-linux-gnu"
GLIBC_LOADER="${GLIBC_LIBDIR}/ld-linux-aarch64.so.1"

deps="glibc-aarch64"

# 用 make 而非 ninja：box64 的 dynarec 生成步骤在 make 下与
# 交叉构建组合为既有验证路径（终端 box64 工作流同为 make）
doNotUseNinja=1

pre_setup() {
  # ---- 0. 编译器选择：x86_64 宿主走交叉，aarch64 宿主走原生 ----
  local _cc="aarch64-linux-gnu-gcc"
  if [[ "$(uname -m)" == "aarch64" ]]; then
    _cc="gcc"
    echo "宿主为 aarch64：使用原生 gcc"
  else
    echo "宿主为 $(uname -m)：使用 aarch64 glibc 交叉编译器 ${_cc}"
  fi
  command -v "${_cc}" >/dev/null 2>&1 || {
    echo "错误: 缺少 aarch64 glibc 编译器 ${_cc}"
    echo "  ubuntu/debian 宿主请安装 gcc-aarch64-linux-gnu（host-deps.sh 已包含）"
    return 1
  }
  for _tool in patchelf readelf; do
    command -v "${_tool}" >/dev/null 2>&1 || {
      echo "错误: 缺少 ${_tool}（box64 加载器钉死与校验必需）"
      return 1
    }
  done

  # ---- 1. 清掉构建系统残留的 NDK 环境 ----
  # 同一轮构建里先跑的项目（含 glibc-aarch64 的 others 流程 /
  # 其它 meson/autotools 项目）会 load_env 导出 NDK clang 与
  # --sysroot CFLAGS；不清掉会污染本交叉 glibc 构建（sysroot
  # 指向 bionic 头文件、include 路径混入 rootfs 的 bionic 头）。
  unset CPPFLAGS CXXFLAGS LDFLAGS LIBS
  unset PKG_CONFIG_LIBDIR
  export CFLAGS="-O2 -pipe"

  # ---- 2. 完整接管 cmake 参数（绕过 NDK Android toolchain） ----
  # 与终端 box64（build-box64.yml）同款选项集：
  #   ARM64 + ARM_DYNAREC   aarch64 动态二进制翻译（跑 x86_64 wine）
  #   WINLATOR_GLIBC        glibc 运行模型 + wine mmap 修正
  #   BAD_SIGNAL            规避信号栈差异
  #   SD845/865/888/8G2     高通系 SoC 的 dynarec 调优（运行期自
  #                         识别，非对应设备时自动走通用路径）
  # NO_CONF_INSTALL/NO_LIB_INSTALL：rootfs 里 /etc 交给 base-config，
  # wrapped 库文件不安装（按原配方行为，运行库走 wine 包闭包）
  # 显式 CMAKE_ASM_COMPILER=aarch64 gcc：杜绝 CMake 在 PATH 里
  # 自行探测出仅支持 x86 的 yasm（v3.2.4 报废根因）
  fullCustomArgs="
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_SYSTEM_NAME=Linux
    -DCMAKE_SYSTEM_PROCESSOR=aarch64
    -DCMAKE_C_COMPILER=${_cc}
    -DCMAKE_ASM_COMPILER=${_cc}
    -DCMAKE_C_COMPILER_LAUNCHER=ccache
    -DCMAKE_INSTALL_PREFIX=${prefix}
    -DARM64=ON
    -DARM_DYNAREC=ON
    -DWINLATOR_GLIBC=ON
    -DBAD_SIGNAL=ON
    -DSD845=ON
    -DSD865=ON
    -DSD888=ON
    -DSD8G2=ON
    -DNO_CONF_INSTALL=ON
    -DNO_LIB_INSTALL=ON
  "

  echo "=== AnWind bionic-box64（aarch64 glibc / WINLATOR_GLIBC）构建开始 ==="
  echo "box64 版本: ${revision}"
  echo "目标加载器: ${GLIBC_LOADER}"
}

install() {
  local _bin="${destDir}${prefix}/local/bin/box64"

  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -p "${srcDir}/${pjName}/build/box64" "${_bin}" \
    || { echo "错误: 未找到构建产物 ${srcDir}/${pjName}/build/box64" && exit 1; }

  # ---- 1. 依赖配方必须已就位（toposort 保证 glibc-aarch64 先构建，
  #         其包已解压进 prefix —— 构建期与设备期同一路径） ----
  if [[ ! -f "${GLIBC_LOADER}" ]]; then
    echo "错误: box64 的私有加载器不存在 => ${GLIBC_LOADER}"
    echo "  glibc-aarch64 配方（deps 已声明）应先于 box64 构建并解压进 prefix"
    exit 1
  fi

  # ---- 2. patchelf 钉死解释器与库搜索路径 ----
  # PT_INTERP 指向 rootfs 内私有 glibc 加载器（终端 box64 同款手法，
  # 参见 termux-x11 start-tfm 与 build-box64.yml）；--force-rpath 生成
  # DT_RPATH（优先级高于 LD_LIBRARY_PATH，glibc 运行库只会从该目录找）
  echo "patchelf: PT_INTERP => ${GLIBC_LOADER}"
  patchelf --set-interpreter "${GLIBC_LOADER}" "${_bin}" \
    || { echo "错误: patchelf --set-interpreter 失败" && exit 1; }
  patchelf --force-rpath --set-rpath "${GLIBC_LIBDIR}" "${_bin}" \
    || { echo "错误: patchelf --set-rpath 失败" && exit 1; }

  # ---- 3. 硬校验：PT_INTERP / RPATH / 架构，任何一项不对直接判失败 ----
  local _interp _machine
  _interp="$(readelf -l "${_bin}" 2>/dev/null \
    | sed -n 's/.*Requesting program interpreter: \(.*\)\]/\1/p' | head -n 1)"
  if [[ "${_interp}" != "${GLIBC_LOADER}" ]]; then
    echo "错误: box64 PT_INTERP 校验失败"
    echo "  期望: ${GLIBC_LOADER}"
    echo "  实际: ${_interp:-（无 PT_INTERP）}"
    exit 1
  fi
  if ! readelf -d "${_bin}" 2>/dev/null | grep -E "RPATH|RUNPATH" \
      | grep -q "${GLIBC_LIBDIR}"; then
    echo "错误: box64 RPATH 校验失败（应包含 ${GLIBC_LIBDIR}）"
    readelf -d "${_bin}" | grep -E "RPATH|RUNPATH" || true
    exit 1
  fi
  _machine="$(readelf -h "${_bin}" 2>/dev/null | awk '/^ *Machine:/{print $2; exit}')"
  if [[ "${_machine}" != "AArch64" ]]; then
    echo "错误: box64 架构校验失败（期望 AArch64，实际 ${_machine:-未知}）"
    exit 1
  fi

  echo "bionic-box64 安装完成: ${_bin}"
  echo "  PT_INTERP: ${_interp}"
  readelf -d "${_bin}" | grep -E "RPATH|RUNPATH" | head -1
}
