revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"

# ============================================================
# AnWind bionic-box64（aarch64 / NDK bionic 链接）
# ------------------------------------------------------------
# 桌面 wine 容器的 x86_64 仿真核心（usr/local/bin/box64）。
# 与 rootfsbc 参考配方（project_legacy/box64）同链构建：NDK clang
# （api=28）+ android.toolchain.cmake（由框架 cmakeBaseArgs 注入），
# 产物为 bionic ELF（PT_INTERP=/system/bin/linker64），与 rootfs 内
# wine / mesa / pulseaudio 等全部二进制的运行形态完全一致。
#
# 【双形态生态】AnWind 同时支持两种 box64 发行形态，rootfs 两种都
# 能承载（本配方的 deps 保证配套运行层随 rootfs 一次到位）：
#   * bionic 形态（本配方，build-box64-bionic.yml 产出
#     anwind-box64-<ver>-aarch64-bionic.tar.gz）：NDK bionic 链接，
#     原生 exec，宿主侧零 glibc 依赖；
#   * glibc 形态（build-box64.yml 产出 anwind-box64-*-aarch64-
#     glibc.tar.gz，Winlator/MiceWine 同款 -DWINLATOR_GLIBC +
#     patchelf）：PT_INTERP/DT_RPATH 钉到 rootfs 私有加载器
#     /data/data/com.anwind/files/rootfs/usr/lib/aarch64-linux-gnu/
#     ld-linux-aarch64.so.1 —— 该加载器与整套 aarch64 glibc 运行库
#     由 glibc-aarch64 配方安装进 rootfs（本包 deps 之一）。
#
# 【deps 说明】glibc-aarch64 / glibc-x86_64 都不是编译期依赖
# （bionic box64 链接 NDK 系统库），而是【运行时配套闭包】——
# 挂在 deps 上保证构建顺序与完整性：
#   * glibc-aarch64 → usr/lib/aarch64-linux-gnu/（私有加载器
#     ld-linux-aarch64.so.1 + libc.so.6 等）：glibc 形态 box64 与
#     glibc 链接的 aarch64 二进制在 rootfs 内的运行层；
#   * glibc-x86_64 → usr/lib/x86_64-linux-gnu/（ld-linux-x86-64.so.2
#     + libc.so.6 等）：被仿真的 x86_64 程序（含 x86_64 wine unix
#     侧）的运行库，box64 经 BOX64_LD_LIBRARY_PATH 查找。
# 因此 `--build box64`（独立构建工作流）与 `--build all`（rootfs
# 全量构建）都会先产出这两层再产出 box64 —— "box64 完整文件集"
# （仿真器本体 + 双 glibc 运行层 + 上游 ANDROID 配套库 + rc + 链接）
# 一次到位，不再出现"只有单个 box64 执行文件"的残缺产物。
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
#   - 上游安装规则（install 段）在 ANDROID 下会把
#     x64android/libc++_shared.so 装进 lib/box64-x86_64-linux-gnu/、
#     system/box64.box64rc 装进 etc/ —— 本配方自定义 install()
#     按同路径落位（$prefix 为 rootfs 绝对前缀，不能用上游的 / 与
#     /usr 默认 DESTINATION）
#
# 【ASM 编译器钉死】load_env 导出 ASM=NDK yasm（仅支持 x86 的
# 汇编器），CMake 的 ASM 探测会命中它 —— arm64_prolog.S 直接
# "yasm: FATAL: unrecognized object format unwind-tables"（v3.2.4
# CI 实报根因；旧配方的 -DCMAKE_ASM_FLAGS= 空参数治不了编译器
# 选择问题）。显式把 ASM 编译器钉到 NDK clang（与 C 同一工具链，
# 集成汇编器原生支持 .S）。
#
# 【产物完整集】缺一不可（install() 逐项硬校验）：
#   local/bin/box64                               仿真器本体
#   bin/box64 → ../local/bin/box64                PATH 入口链接
#     （rootfs 的 profile 只把 $prefix/bin 放进 PATH，usr-merge 后
#     即 rootfs/usr/bin —— 终端直接敲 box64 / anwind-wine 的
#     find_box64 第二探测位均命中该链接）
#   lib/box64-x86_64-linux-gnu/libc++_shared.so  x86_64 C++ 标准库
#     （上游 x64android/ 预制产物：被仿真的 x86_64 程序 dlopen
#     libc++_shared.so 时经 BOX64_LD_LIBRARY_PATH 从该目录解析；
#     wine 的 glibc 系 x86_64 运行库另由 glibc-x86_64 配方安装在
#     lib/x86_64-linux-gnu，两者职责不同、互不替代）
#   etc/box64.box64rc                             系统级调优开关
# ============================================================

# 与 rootfsbc 参考配方（project_legacy/box64）同参数集：
# ANDROID + BAD_SIGNAL + ARM_DYNAREC；BAD_SIGNAL 在 ANDROID=ON 时
# 上游本就自动置 ON（CMakeLists if(ANDROID) set(BAD_SIGNAL ON)），
# 显式写出以对齐参考语义。NO_CONF_INSTALL / NO_LIB_INSTALL 不
# 保留 —— 两者正是产物只剩单个 box64 文件的原因（配套的
# x64android/libc++_shared.so 与 system/box64.box64rc 被 install
# 规则跳过），由自定义 install() 补齐完整集。
#
# 【v3.2.13 · NOLOADADDR=ON】（用户实测"bionic box64 启动时没有任何日志、
# 无法启动"的排查修复之一，与 Winlator / MiceWine 两条已验证的 Android
# bionic 部署链对齐）：
#   上游默认（NOLOADADDR=OFF）经 lld `--image-base=0x34800000` 把 box64
#   钉在低地址 —— 这是桌面 glibc ld.so 的手法。Android 的 linker64 对 PIE
#   不遵守 image-base（ASLR 随机化加载基址），该链接参数在设备上零收益；
#   且 0x34800000 低地址段在 Android 应用进程布局里与 zygote/heap 常驻区
#   相邻，不同 ROM 的 mmap_min_addr / 布局差异下存在加载即死的风险面。
#   Winlator（WINLATOR_GLIBC 链）与 MiceWine（NDK bionic 链）的 box64
#   构建【均显式 -DNOLOADADDR=ON】，本配方对齐 —— box64 运行时按
#   /proc/self/maps 自适应任意基址（Termux 同款形态）。
args="-DANDROID=ON -DBAD_SIGNAL=ON -DARM_DYNAREC=ON -DNOLOADADDR=ON"

# 运行时配套闭包（见头部【deps 说明】：非编译期依赖，是保证
# "完整文件集"一次到位的构建顺序声明）
deps="glibc-aarch64 glibc-x86_64"

# 用 make 而非 ninja：与 rootfs 其余 cmake 包同一验证路径
doNotUseNinja=1

pre_setup() {
  # ---- v3.2.13 · 强制 wrapper 再生成（确定性根治 mtime 竞态） ----
  # box64 仓库把 src/wrapped/generated/functions_list.txt（与 wrapper.c）
  # 预生成后【随源码提交】—— 且提交版是【无 ANDROID 特性位】的生成物
  # （v0.4.4 实测：7651 行，无 shmget/androidshmem）。CMake 的
  # add_custom_command(OUTPUT functions_list.txt) 按 mtime 判断是否重跑：
  # git clone 后全部文件 mtime 都是"现在"，谁更新纯粹取决于检出写入顺序
  # —— 若 functions_list.txt 恰好不旧于 wrappedandroidshmem.c，make 跳过
  # 再生成 → 构建出的 box64 wrapper 表缺 Android shm 系列（wine 的 X11/
  # unix 侧大量依赖 SysV shm）→ 运行时符号解析错乱、加载即死、零输出
  # —— 正是"启动时没有任何日志、box64 无法启动"的形态。
  # 删掉 OUTPUT 文件即可强制 add_custom_command 必跑（make 语义：OUTPUT
  # 缺失 = 无条件执行），ANDROID 特性位（CMake 命令行参数）随次带入。
  if [[ -f "${srcDir}/${pjName}/src/wrapped/generated/functions_list.txt" ]]; then
    echo "box64: 删除预生成的 wrapper 清单，强制按 ANDROID 特性位再生成"
    rm -f "${srcDir}/${pjName}/src/wrapped/generated/functions_list.txt"
  fi

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

  # ---- 4. PATH 入口链接：usr/bin/box64 → ../local/bin/box64 ----
  # 相对链接在 $prefix（rootfs 绝对前缀）与 tar 解包后均成立；
  # anwind-container 的 find_box64 第二探测位即 $RFS_USR/bin/box64
  mkdir -p "${destDir}${prefix}/bin/"
  ln -sfn ../local/bin/box64 "${destDir}${prefix}/bin/box64"

  # ---- 5. strip（发布体积；llvm-strip 是 NDK 自带） ----
  if [[ -x "${_strip}" ]]; then
    "${_strip}" "${_bin}" || true
  fi

  # ---- 6. 硬校验：aarch64 ELF + bionic PT_INTERP，任何一项不对直接判失败 ----
  local _machine _interp
  _machine="$(readelf -h "${_bin}" 2>/dev/null | awk '/^ *Machine:/{print $2; exit}')"
  if [[ "${_machine}" != "AArch64" ]]; then
    echo "错误: box64 架构校验失败（期望 AArch64，实际 ${_machine:-未知}）"
    return 1
  fi
  _interp="$(readelf -l "${_bin}" 2>/dev/null \
    | sed -n 's/.*Requesting program interpreter: \(.*\)\]/\1/p' | head -n 1)"
  if [[ "${_interp}" != "/system/bin/linker64" ]]; then
    echo "错误: 本配方产物应为 bionic 形态（NDK 链接），PT_INTERP 校验失败"
    echo "  期望: /system/bin/linker64"
    echo "  实际: ${_interp:-（无 PT_INTERP）}"
    echo "  注: glibc 形态（PT_INTERP=ld-linux-aarch64.so.1）是 build-box64.yml"
    echo "       独立工作流的产物，不应出现在本配方链里 —— 查构建参数/工具链"
    return 1
  fi
  # 入口链接自检（相对目标存在）
  if [[ ! -e "${destDir}${prefix}/bin/box64" ]]; then
    echo "错误: ${prefix}/bin/box64 入口链接失效"
    return 1
  fi

  # ---- 6.5 v3.2.13 · 冒烟校验（wrapper 生成 + 内建符号 + NOLOADADDR 落地） ----
  # 用户实测"启动时没有任何日志、box64 无法启动"且旧 CI 静态校验全绿 ——
  # 说明纯 ELF 头校验挡不住"源码级/配置级构建错误"。此处补三道与运行时
  # 行为直接挂钩的校验（构建树未 strip，符号完整可查）：
  #   a) wrapper 再生成确证：rebuild_wrappers.py 必须带着 ANDROID 特性位跑过
  #      —— functions_list.txt 里应能找到 wrappedandroidshmem 的 shmget（ANDROID
  #      特有 wrapper，漏跑/错位生成的直接证据）。wrapper 是 box64 拦截转发
  #      x86_64 符号请求的核心表，生成错 = 运行时符号解析全错（典型表现即
  #      静默崩溃零输出）。
  #   b) 未 strip 二进制符号表抽查：box64 内部应存在 shmget 包装与 dynarec
  #      核心（find_next_dynarec_mem）——缺失说明 DYNAREC/ANDROID 编译单元
  #      没被链进来（cmake 选项没生效的兜底证据）。
  #   c) NOLOADADDR 落地确证：链接命令不得残留 --image-base / -no-pie
  #      （v3.2.13 加 -DNOLOADADDR=ON 的配套自检，防上游 CMake 行为漂移）。
  if [[ -f "${_src}/src/wrapped/generated/functions_list.txt" ]]; then
    if ! grep -q "shmget" "${_src}/src/wrapped/generated/functions_list.txt"; then
      echo "错误: wrapper 未按 ANDROID 特性位再生成（functions_list.txt 无 shmget）"
      echo "  rebuild_wrappers.py 的 ANDROID 参数决定 androidshmem 包装是否进入"
      echo "  wrapper 表；漏跑 = 运行时 shm 系列符号解析错乱（静默崩溃零输出）"
      return 1
    fi
    echo "冒烟[1/3]: wrapper 含 ANDROID shmget 包装 ✓"
  else
    echo "警告: 未找到 src/wrapped/generated/functions_list.txt（上游源码布局变化？）"
  fi
  local _sym_ok=1
  for _sym in shmget getDB; do
    if ! nm "${_src}/build/box64" 2>/dev/null | grep -qw "${_sym}"; then
      echo "错误: box64 符号表缺 ${_sym}（ANDROID/DYNAREC 编译单元未链接？）"
      _sym_ok=0
    fi
  done
  [[ "${_sym_ok}" == "1" ]] \
    && echo "冒烟[2/3]: 符号表含 shmget/getDB（ANDROID shm 包装 + DYNAREC 核心）✓" \
    || return 1
  if grep -q "image-base\|no-pie" "${_src}/build/CMakeFiles/box64.dir/link.txt" 2>/dev/null; then
    echo "错误: 链接命令残留 --image-base/-no-pie（NOLOADADDR 未生效）"
    grep -h "image-base\|no-pie" "${_src}/build/CMakeFiles/box64.dir/link.txt"
    return 1
  fi
  echo "冒烟[3/3]: 无 --image-base/-no-pie（NOLOADADDR 已生效）✓"

  # ---- 7. 配套运行层就位报告（deps 保证已装进 prefix，纯诊断） ----
  [[ -e "${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" ]] \
    && echo "配套 aarch64 glibc 层: ${prefix}/lib/aarch64-linux-gnu/（glibc 形态 box64 / glibc 二进制运行层）" \
    || echo "警告: prefix 缺 aarch64-linux-gnu（glibc-aarch64 未先行安装？）"
  [[ -e "${prefix}/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ]] \
    && echo "配套 x86_64 glibc 层: ${prefix}/lib/x86_64-linux-gnu/（被仿真程序运行库）" \
    || echo "警告: prefix 缺 x86_64-linux-gnu（glibc-x86_64 未先行安装？）"

  echo "bionic-box64 安装完成:"
  echo "  ${_bin}（PT_INTERP => /system/bin/linker64）"
  echo "  ${prefix}/bin/box64 → ../local/bin/box64（PATH 入口）"
  echo "  ${prefix}/lib/box64-x86_64-linux-gnu/libc++_shared.so"
  echo "  ${prefix}/etc/box64.box64rc"
}
