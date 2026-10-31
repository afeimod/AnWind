revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"

# ============================================================
# AnWind bionic-box64（aarch64 / NDK bionic 链接）
# ------------------------------------------------------------
# 桌面 wine 容器的 x86_64 仿真核心（/usr/local/bin/box64）。
# 与 rootfsbc 体系同链构建：NDK clang（api=28）+
# android.toolchain.cmake（由框架 cmakeBaseArgs 注入），产物为
# bionic ELF（PT_INTERP=/system/bin/linker64），与 rootfs 内
# wine / mesa / pulseaudio 等全部二进制的运行形态完全一致 ——
# rootfs 是 bionic 系统，box64 就是 bionic 二进制，不需要也不
# 应当引入任何 glibc 产物（宿主侧 libc 与仿真侧无关：box64 的
# elfloader 只检测被仿真程序的 PT_INTERP 存在性，从不执行它）。
#
# 【历史教训：glibc 方案已废弃】v3.2.4~v3.2.5 曾改为
# aarch64-linux-gnu-gcc 交叉构建 + WINLATOR_GLIBC + patchelf 把
# PT_INTERP 钉到 rootfs 私有加载器（Ubuntu glibc deb 解包的
# ld-linux-aarch64.so.1 + libc.so.6 等，glibc-aarch64 配方）——
# 该形态与 bionic rootfs 不相容，设备上连 box64 自身都起不来
# （"连 box64 的启动都没有"），且把终端 glibc 体系（另一套系统）
# 的模型错误搬进 bionic。glibc-aarch64 配方同步删除。
#
# 【上游 ANDROID 模式】（CMakeLists.txt option(ANDROID)）：
#   - add_definitions(-DANDROID)；BAD_SIGNAL 自动 ON（上游对
#     Android 信号栈差异的规避 —— wine ntdll unix 侧依赖）
#   - 内建 wrappedandroidshmem.c：被仿真 x86_64 程序的 SysV shm
#     系列调用由 box64 拦截转发 bionic，rootfs 无需 libandroid-shmem
#   - 链接 bionic 系统库 c / m / dl，无外部运行时依赖
#   - Release 优化限制为 -O1（上游对 Android dynarec 稳定性的
#     选择，CMakeLists "limit the optimization level" hack，
#     会覆盖框架 cflags 的 -O3，遵上游）
#   - ARM64 / ARM_DYNAREC 由 toolchain 的 CMAKE_SYSTEM_PROCESSOR=
#     aarch64 自动使能，跑 x86_64 wine 的动态二进制翻译
#
# 【ASM 编译器钉死】load_env 导出 ASM=NDK yasm（仅支持 x86 的
# 汇编器），CMake 的 ASM 探测会命中它 —— arm64_prolog.S 直接
# "yasm: FATAL: unrecognized object format unwind-tables"
# （v3.2.4 报废根因；旧配方的 -DCMAKE_ASM_FLAGS= 空参数治不了
# 编译器选择问题）。显式把 ASM 编译器钉到 NDK clang（与 C 同一
# 工具链，集成汇编器原生支持 .S）。
#
# 【产物完整集】上游 ANDROID 模式的安装内容，缺一不可：
#   local/bin/box64                               仿真器本体
#   lib/box64-x86_64-linux-gnu/libc++_shared.so  x86_64 C++ 标准库
#     （上游 x64android/ 预制产物：被仿真的 x86_64 程序 dlopen
#     libc++_shared.so 时从该目录解析；wine 的 glibc 系 x86_64
#     运行库另由 glibc-x86_64 配方安装在 lib/x86_64-linux-gnu，
#     两者职责不同、互不替代）
#   etc/box64.box64rc                             系统级调优开关
# ============================================================

# 与 rootfsbc 参考配方（project_legacy/box64）同参数集：
# ANDROID + BAD_SIGNAL + ARM_DYNAREC；BAD_SIGNAL 在 ANDROID=ON 时
# 上游本就自动置 ON（CMakeLists if(ANDROID) set(BAD_SIGNAL ON)），
# 显式写出以对齐参考语义。NO_CONF_INSTALL / NO_LIB_INSTALL 不再
# 保留 —— 两者正是产物只剩单个 box64 文件的原因（配套的
# x64android/libc++_shared.so 与 system/box64.box64rc 被 install
# 规则跳过），本次由自定义 install() 补齐完整集
args="-DANDROID=ON -DBAD_SIGNAL=ON -DARM_DYNAREC=ON"

# 用 make 而非 ninja：与 rootfs 其余 cmake 包同一验证路径
doNotUseNinja=1

pre_setup() {
  # 钉死 ASM 编译器为 NDK clang（v3.2.4 yasm 事故根治）。
  # 双保险：-D 进 CMake cache（探测最高优先级）+ 覆盖 env ASM
  # （load_env 导出的 ASM 是 NDK yasm，仅支持 x86，是探测的
  # 次优先来源；empty.sh 会在包间 unset，无跨包泄漏）。
  # NDK toolchain file 若自行设置 ASM 也只会设成同款 clang。
  local _clang="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin/${targetArch}-linux-android${api}-clang"
  if [[ ! -x "${_clang}" ]]; then
    # 兜底：NDK 尚未解压就位时用 PATH 名（构建系统在 configure 前
    # 保证 NDK 就绪；aarch64 本地构建场景同样适用）
    _clang="${targetArch}-linux-android${api}-clang"
  fi
  args+=" -DCMAKE_ASM_COMPILER=${_clang}"
  export ASM="${_clang}"
}

install() {
  local _src="${srcDir}/${pjName}"
  local _bin="${destDir}${prefix}/local/bin/box64"
  local _strip="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

  # ---- 1. box64 本体 ----
  if [[ ! -f "${_src}/build/box64" ]]; then
    echo "错误: 未找到构建产物 ${_src}/build/box64"
    return 1
  fi
  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -p "${_src}/build/box64" "${_bin}"

  # ---- 2. x86_64 C++ 标准库（上游 ANDROID 模式配套，勿漏） ----
  if [[ ! -f "${_src}/x64android/libc++_shared.so" ]]; then
    echo "错误: 未找到上游 x64android/libc++_shared.so（${_src}）"
    return 1
  fi
  mkdir -p "${destDir}${prefix}/lib/box64-x86_64-linux-gnu/"
  cp -p "${_src}/x64android/libc++_shared.so" \
    "${destDir}${prefix}/lib/box64-x86_64-linux-gnu/"

  # ---- 3. 系统级 box64 rc ----
  mkdir -p "${destDir}${prefix}/etc/"
  cp -p "${_src}/system/box64.box64rc" "${destDir}${prefix}/etc/"

  # ---- 4. strip（发布体积；llvm-strip 是 NDK 自带） ----
  if [[ -x "${_strip}" ]]; then
    "${_strip}" "${_bin}" || true
  fi

  # ---- 5. 硬校验：aarch64 ELF + bionic PT_INTERP，任何一项不对直接判失败 ----
  local _machine _interp
  _machine="$(readelf -h "${_bin}" 2>/dev/null | awk '/^ *Machine:/{print $2; exit}')"
  if [[ "${_machine}" != "AArch64" ]]; then
    echo "错误: box64 架构校验失败（期望 AArch64，实际 ${_machine:-未知}）"
    return 1
  fi
  _interp="$(readelf -l "${_bin}" 2>/dev/null \
    | sed -n 's/.*Requesting program interpreter: \(.*\)\]/\1/p' | head -n 1)"
  if [[ "${_interp}" != "/system/bin/linker64" ]]; then
    echo "错误: box64 PT_INTERP 非 bionic 形态"
    echo "  期望: /system/bin/linker64（NDK bionic 链接）"
    echo "  实际: ${_interp:-（无 PT_INTERP）}"
    echo "  若为 ld-linux-aarch64.so.1 => 又混入了 glibc 方案，查 pre_setup/toolchain"
    return 1
  fi

  echo "bionic-box64 安装完成:"
  echo "  ${_bin}（PT_INTERP => /system/bin/linker64）"
  echo "  ${prefix}/lib/box64-x86_64-linux-gnu/libc++_shared.so"
  echo "  ${prefix}/etc/box64.box64rc"
}
