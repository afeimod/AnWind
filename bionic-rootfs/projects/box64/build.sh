revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="others"
license="MIT"
deps="glibc-aarch64"

# ============================================================
# box64（aarch64 / ARM_DYNAREC）—— 桌面 wine 容器的 x86_64 后端
# ------------------------------------------------------------
# 【v3.2.6 根因修复】此前用 -DANDROID=ON 以 NDK/bionic 目标构建
# （MiceWine 同款参数）：box64 本体能跑（冒烟 `box64 wine
# --version` 正常），但 wineboot 全量初始化在 ntdll unix 侧
# 瞬间 SIGSEGV、零输出——bionic 版 box64 缺 glibc 运行语义，
# 撑不起 wine 的完整启动。
# 设备侧根因（用户实测定位）：
#   1. rootfs 内没有 box64 需要的 ld-linux-aarch64.so.1；
#   2. box64 构建后没有 patchelf --set-interpreter 指向它。
# 修复（全部在 bionic-rootfs 体系内完成，与 glibc-x86_64 配方
# 同一思路，不引入任何体系外的 glibc 环境）：
#   * projects/glibc-aarch64 配方（本配方的 deps）把 Ubuntu
#     noble(arm64) 的 glibc 运行库平铺进
#       ${prefix}/lib/aarch64-linux-gnu/
#     其中 ld-linux-aarch64.so.1 是本配方 patchelf 的钉死目标；
#     bionic 加载器永不搜索该目录，与 rootfs 自身库零串扰
#   * 本配方改用宿主交叉工具链 aarch64-linux-gnu-gcc 按
#     Linux/glibc 目标构建（noble 同源 glibc 2.39，编译期与
#     运行期完全匹配；box64 上游为纯 C+ASM 项目，无需交叉 g++）
#   * patchelf 把 box64 的 PT_INTERP 钉死到私有加载器、
#     RUNPATH 指向私有库目录——进程内只有这一份 glibc，
#     不与 bionic 混载
# 宿主依赖（host-deps.sh 已声明）：
#   gcc-aarch64-linux-gnu、patchelf
# 版本可用 ANWIND_BOX64_REF 覆盖（默认 v0.4.4：对齐
# hangover-11.16 官方配套；hangover↔box64 的 PE/wow64 协议
# 版本敏感，错配可致 wineboot SEH 级联崩溃）。
# 产物：${prefix}/local/bin/box64（安装路径与旧版一致，App 侧
# anwind-wine/anwind-container 的探测逻辑无需任何改动）
# ============================================================

extra_fuction() {
  local _glibcLib="${prefix}/lib/aarch64-linux-gnu"
  local _loader="${_glibcLib}/ld-linux-aarch64.so.1"
  local _crossCC="aarch64-linux-gnu-gcc"

  # ---- 前置硬校验：交叉工具链 / patchelf / 私有 glibc 加载器 ----
  command -v "${_crossCC}" >/dev/null 2>&1 || {
    echo "错误: 缺少 ${_crossCC}"
    echo "  → Debian/Ubuntu: apt install gcc-aarch64-linux-gnu（host-deps.sh 已声明）"
    exit 1
  }
  command -v patchelf >/dev/null 2>&1 || {
    echo "错误: 缺少 patchelf（host-deps.sh 已声明）"
    exit 1
  }
  [[ -f "${_loader}" ]] || {
    echo "错误: 缺少 ${_loader}"
    echo "  → box64 需要 bionic-rootfs 体系内的 aarch64 glibc 私有加载器；"
    echo "  → 本配方已声明 deps=\"glibc-aarch64\"，构建系统会先行构建并解包到 prefix。"
    echo "  → 出现本错误通常意味着用 --has-prefix 跳过了解包，或 prefix 被清空。"
    exit 1
  }
  echo "私有 glibc 加载器就绪: ${_loader}"

  # ---- glibc 交叉环境（覆盖 load_env 注入的 NDK/bionic 设置） ----
  # 构建系统对 buildSys=others 会先 load_env 注入 NDK 环境变量；
  # 这里显式切到宿主 glibc 交叉工具链（不 sysroot 到 NDK）。
  export CC="${_crossCC}"
  export CFLAGS="-O2 -pipe"
  export LDFLAGS=""
  unset PKG_CONFIG_LIBDIR

  # ---- 构建（Linux/glibc 目标 + ARM_DYNAREC） ----
  # CMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY：交叉环境下禁止
  # try_compile 生成可执行文件（宿主跑不了 aarch64 ELF）。
  # 不再传 -DANDROID=ON（那是 bionic 目标的开关，正是本次修复
  # 要去掉的构建形态）；BAD_SIGNAL 保留：规避内核 SIGSEGV
  # si_info 不一致（设备内核层差异，与 libc 无关）。
  rm -rf build
  mkdir build
  cd build
  cmake .. \
    -DCMAKE_SYSTEM_NAME=Linux \
    -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
    -DCMAKE_C_COMPILER="$(command -v "${_crossCC}")" \
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DARM_DYNAREC=ON \
    -DBAD_SIGNAL=ON \
    -DNO_CONF_INSTALL=ON \
    -DNO_LIB_INSTALL=ON \
    || { echo "错误: box64 cmake 配置失败" && exit 1; }
  cmake --build . --parallel "$(nproc)" \
    || { echo "错误: box64 编译失败" && exit 1; }
  [[ -f box64 ]] || { echo "错误: 未生成 build/box64" && exit 1; }
  echo "box64 编译完成: $(du -h box64 | cut -f1)"

  # ---- patchelf：钉死私有加载器与运行库搜索路径 ----
  # 解释器路径比默认 .interp 槽位长（83 字符 vs 28 字符），
  # patchelf 会自动重定位 .interp 段，无需预留空间（已实测验证）。
  patchelf --set-interpreter "${_loader}" \
           --set-rpath "${_glibcLib}" \
           box64 \
    || { echo "错误: patchelf 改写 box64 失败" && exit 1; }

  # ---- 改写硬校验：PT_INTERP / RUNPATH 必须精确命中 ----
  readelf -l box64 | grep -F "${_loader}" >/dev/null \
    || { echo "错误: PT_INTERP 未指向私有加载器（patchelf 未生效？）" && exit 1; }
  readelf -d box64 | grep -F "${_glibcLib}" >/dev/null \
    || { echo "错误: RUNPATH 未指向私有 glibc 目录" && exit 1; }
  echo "PT_INTERP => ${_loader}"
  echo "RUNPATH   => ${_glibcLib}"

  # ---- 安装 ----
  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -p box64 "${destDir}${prefix}/local/bin/" \
    || { echo "错误: box64 安装失败" && exit 1; }
  echo "box64 安装完成: ${prefix}/local/bin/box64（glibc 私有运行时，解释器已钉死）"
}
