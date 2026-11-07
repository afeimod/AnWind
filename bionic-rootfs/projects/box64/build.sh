revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"

# ============================================================
# v3.3.10（box64 构建修复，用户问题 3）：0001 补丁按版本条件生效
# ------------------------------------------------------------
# 【事故回放】CI 日志 build-box64-aarch64.log：选定非 v0.4.4 的
# revision（master 或旧版）时，0001-anwind-android-shmem-native.patch
# 在 src/elfs/elfparser.c 的 hunk 失败（CMakeLists.txt 两 hunk 带偏移
# 勉强过、elfparser 直接 FAIL）→ apply_patches 判构建阵亡。
# 【修复策略】补丁基线 v0.4.4，两层防线：
#   1. 版本前置判断（用户口径"0.4.4 以前的 box64 不能接受一些补丁，
#      在选择时跳过"）：revision 解析出版本号 < 0.4.4 时直接
#      doNotApplyPatch=1（整包跳过，日志明示）；
#   2. patchOptional=1 兑底：其余 ref（master 漂移/未来新版）上 dry-run
#      失败时由框架【警告跳过】而非中止（patchesSkipped 记录），
#      install() 据实条件化 shm 冒烟断言 —— 跳过补丁的构建产物不再
#      因缺 shm 符号被误判失败。
# 补丁未生效的后果由用户自担（bionic 宿主 shm 链路断、Android RELR
# 重定位静默跳过 → wineboot SIGSEGV 风险面），日志大字提示。
# ============================================================
_patch_baseline_major=0
_patch_baseline_minor=4
_patch_baseline_patch=4
_box64_ver_major="$(echo "${revision}" | sed -n 's/^[vV]\?\([0-9]\+\)\..*$/\1/p')"
_box64_ver_minor="$(echo "${revision}" | sed -n 's/^[vV]\?[0-9]\+\.\([0-9]\+\).*/\1/p')"
_box64_ver_patch="$(echo "${revision}" | sed -n 's/^[vV]\?[0-9]\+\.[0-9]\+\.\([0-9]\+\).*/\1/p')"
if [[ -n "${_box64_ver_major}" ]]; then
  # 解析出版本号（如 v0.4.4 / 0.4.6）：与基线 0.4.4 逐段比较
  _box64_lt_baseline=0
  if (( _box64_ver_major < _patch_baseline_major )); then
    _box64_lt_baseline=1
  elif (( _box64_ver_major == _patch_baseline_major )) && (( _box64_ver_minor < _patch_baseline_minor )); then
    _box64_lt_baseline=1
  elif (( _box64_ver_major == _patch_baseline_major )) && (( _box64_ver_minor == _patch_baseline_minor )) && (( ${_box64_ver_patch:-0} < _patch_baseline_patch )); then
    _box64_lt_baseline=1
  fi
  if (( _box64_lt_baseline == 1 )); then
    # v3.3.11：顶层 echo 必须 >&2 —— get_dep() 以 $(...) 捕获本文件 stdout
    # 作为依赖列表，裸 echo 会把 "[INFO]" 混进 deps，collect_dep 把它当
    # 包名报 "项目不存在 => [INFO]"（CI 实测，box64 相关构建全挂）
    echo "[INFO] box64 revision=${revision} < v${_patch_baseline_major}.${_patch_baseline_minor}.${_patch_baseline_patch}（补丁基线）—— 0.4.4 以前的版本不接受 0001 补丁，按选择跳过" >&2
    doNotApplyPatch=1
  else
    echo "[INFO] box64 revision=${revision} >= 补丁基线 v0.4.4，正常应用 0001 补丁（漂移时 patchOptional 跳过）" >&2
    patchOptional=1
  fi
else
  # master / main / 分支名：无法解析版本 → patchOptional 兑底跳过
  echo "[INFO] box64 revision=${revision} 非版本号（分支/漂移 ref）—— 补丁 dry-run 失败时将跳过（patchOptional=1）" >&2
  patchOptional=1
fi

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
#   - wrappedandroidshmem.c：标准 wrappedlib 壳（对 libandroid-shmem.so
#     的纯 GO 型运行时 dlsym 映射，仅服务显式 dlopen 该库的程序）
#     —— rootfs 无需 libandroid-shmem
#   - v3.2.18 补丁 0001-anwind-android-shmem-native.patch【补齐缺的东西】：
#     上游 v0.4.4/main 实证（本仓沙箱全量复现）：box64 对 x86_64 程序的
#     shm 链路在 Android 上【两条都是断的】：
#       a) 动态程序：wrappedlibc 的 GO(shmget 等) 桥接 dlsym 宿主 libc ——
#          glibc 宿主能解析（x86_64 CI 冒烟通过的原因），bionic 宿主
#          根本没有 shm 符号 → "function not found"（用户 CI 日志
#          "符号表缺 shmget" 指的就是这个缺口）；
#       b) 静态程序：x64Syscall_linux 的 switch 无 case 29/30/31/67
#          → ENOSYS（沙箱实测 vanilla v0.4.4 跑静态 shm 冒烟：
#          "shmget: Function not implemented"）；
#       c) Android 内核普遍无 CONFIG_SYSVIPC，直接转发内核也不通。
#     补丁三处联动：src/libtools/android_shmem.c（memfd_create 后端的
#     内建 SysV shm 实现，含 /dev/ashmem 兼容回退；导出 shmget/shmat/
#     shmctl/shmdt + my_* 别名）+ wrappedlibc_private.h（shm 四符号在
#     ANDROID 下 GO→GOM，桥接到 my_* 内建实现）+ x64syscall.c（主
#     switch 补 case 29/30/31/67，ANDROID 下转发内建实现、glibc 宿主
#     直用 libc —— 静态程序也修好）。glibc 形态构建不受影响（文件在
#     非 ANDROID 下编译为空、private 头走 GO 原分支）。
#   - v3.2.20 补丁新增（wineboot SIGSEGV 根因修复，全形态生效）：
#     NDK r29 lld 默认给 bionic 链接的 .so 发 Android 标签 RELR 打包
#     重定位（DT_ANDROID_RELR=0x6fffe000/RELRSZ/RELRENT，编码同标准
#     RELR 仅标签号不同），box64 v0.4.4 的 elfparser 只认标准 DT_RELR
#     系列 → 打包的 R_X86_64_RELATIVE 全部静默跳过 → bionic wine
#     x86_64-unix/ntdll.so 的 .data 自指针（virtual.c 的
#     reserved_areas = LIST_INIT(reserved_areas)）保持链期裸地址，
#     wineboot 一进 mmap_add_reserved_area 即 SIGSEGV（设备日志
#     ntdll.so+0x770a8 读 0x928e8，R15=0x928d8 裸值 vs RAX=
#     0x3f000928d8 正确重定位值 —— 用 bionic-wine-official-9.2-x86_64
#     发布包的 ntdll.so 逐字节核实：.rela.dyn 仅 7 项，其余全在
#     .relr.dyn/SHT_ANDROID_RELR 的 14 个打包项里）。补丁给
#     elfparser.c/elfparser32.c 的 DT_RELR/RELRSZ/RELRENT case 加
#     0x6fffe000/0x6fffe001/0x6fffe003 别名；沙箱实测旧 box64 加载
#     该 ntdll.so 无 "Applying N Relocation(s) without Addend" 行
#     （重定位被跳过），打补丁后 14 项全应用。宿主冒烟新增
#     [ANDROID RELR] 回归（gcc 标准标签 + python 翻 Android 标签，
#     经 box64 实跑断言 —— 见 build-box64-bionic.yml）。
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
  # ---- wrapper 生成确定性（注释勘误 + 操作保留） ----
  # functions_list.txt 只是 rebuild_wrappers.py 的变更缓存对比文件；
  # 删除它强制 add_custom_command 必跑，wrapper 按本机 python 确定性
  # 再生成（无 mtime 竞态）。注意：本框架 cmake 不传 -DCI=ON，生成
  # 规则（if(NOT CI)）存在，删除后可重建；CI=ON 的独立冒烟构建
  # 使用提交版生成物，不受此操作影响。
  if [[ -f "${srcDir}/${pjName}/src/wrapped/generated/functions_list.txt" ]]; then
    echo "box64: 删除预生成的 wrapper 清单，强制确定性再生成"
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

  # ---- 6.5 v3.2.18 · 冒烟校验（内建 shm 实现 + DYNAREC + NOLOADADDR） ----
  # 【历史教训链，勿再绕回弯路】
  #   v3.2.13 校验 functions_list.txt 含 shmget → 误报（纯 GO 型符号
  #     不进该缓存文件）；
  #   v3.2.14 校验符号表含 shmget/shmat/shmctl → 误报（动态构建的
  #     GO 型包装符号从不进 box64 符号表，上游 v0.4.4 与 main 实证）；
  #   v3.2.15 改查 getDB/FreeDynablock + 编译单元 + 注册字符串 → 校验
  #     本身正确，但用户点破的“缺东西”是真命题：shm 链路在 bionic 上
  #     两条全断（见头部【v3.2.18 补丁】段），符号表里没有 shm 符号
  #     只是症状；
  #   v3.2.18 用补丁把缺口真正补上：ANDROID 构建导出【真实的】
  #     shmget/shmat/shmctl/shmdt（memfd 后端实现，非包装壳），符号表
  #     直查由此【恢复为合法校验】——缺符号 = 补丁没打上/没编进，
  #     正是本轮要挡住的事故面。
  #   v3.2.21 补上真正缺的最后一环（用户 CI 日志“shm 符号未进动态导出表”
  #     警告的根因）：上游 CMakeLists 全局 -fvisibility=hidden ——
  #     HIDDEN 符号永不进 .dynsym（link.txt 里明明有 --export-dynamic：
  #     隐藏全局被降级为局部符号，nm 显示小写 t，符号表直查照样绿 =>
  #     v3.2.18~v3.2.20 的冒烟[1/2] 一直是假阳性）；且 my_* 别名无任何
  #     静态引用，被 NDK lld 默认 --gc-sections 整段回收。运行时桥接
  #     dlsym(dlopen(NULL), "my_shmget")（library.c getSymbolInSymbolMaps）
  #     双杀全断。修复：八符号强制默认可见性（visibility("default")，
  #     同上游 EXPORT 宏手法 my_XShmCreateImage），默认可见 +
  #     --export-dynamic => 进 .dynsym 且成 GC 根，一石二鸟。
  #     .dynsym 校验由此从【警告】升级为【硬错误】—— v3.2.20 曾以警告
  #     带病发布，今后缺导出直接失败。
  # 校验组（全部在构建树产物上查，strip 不影响）
  #   a) shm 四符号（v3.2.18 内建实现的真实导出）
  #   b) DYNAREC 核心真符号：getDB / FreeDynablock（dynablock.c 真函数
  #      的导出符号；v0.4.4 与最新 main 均稳定存在）
  #   c) wrappedandroidshmem.c 编译单元在链 + libandroid-shmem.so
  #      注册字符串（.rodata，strip 不受影响）
  #   d) NOLOADADDR 落地：链接命令不得残留 --image-base / -no-pie
  #      （v3.2.13 加 -DNOLOADADDR=ON 的配套自检，防上游 CMake 行为漂移）
  #
  # v3.3.10（box64 构建修复）：shm 断言组【仅在 0001 补丁实际在链时执行】。
  # 补丁在链的判据 = 源码树存在补丁引入的 src/libtools/android_shmem.c
  # 且含 ANW_EXPORT 特征（源码复用分支 patchesApplied 变量为空、不能作
  # 依据；查源码树特征对"重新获取+跳过补丁/复用旧树+已打补丁"两种
  # 路径都成立）。补丁被跳过（<0.4.4 旧版 / master 漂移）时：
  #   * shm 四符号 / .dynsym 导出 / 注册字符串三项跳过（必然缺，
  #     误判失败毫无意义），改打一行大字警告 —— 后果见头部说明；
  #   * DYNAREC 核心（getDB/FreeDynablock）与 NOLOADADDR 断言照常执行
  #     （不依赖补丁，架构完整性仍受保护）。
  local _patch_in_tree=0
  if [[ -f "${_src}/src/libtools/android_shmem.c" ]] \
     && grep -q 'ANW_EXPORT' "${_src}/src/libtools/android_shmem.c" 2>/dev/null; then
    _patch_in_tree=1
  fi
  if [[ "${_patch_in_tree}" == "0" ]]; then
    echo "=============================================================="
    echo "⚠️⚠️ 0001-anwind-android-shmem-native.patch 未在链（revision=${revision}"
    echo "    低于补丁基线 v0.4.4 或 ref 漂移被跳过）—— 本产物为【未打补丁】形态："
    echo "    * bionic 宿主上 shm 链路两条全断（静态程序 ENOSYS / 动态程序"
    echo "      function not found）—— wine 需要 SysV shm 的路径会失败；"
    echo "    * NDK lld 的 Android 标签 RELR 重定位（DT_ANDROID_RELR）不识别"
    echo "      —— bionic wine ntdll 的 .data 自指针保持裸地址，wineboot 有"
    echo "      SIGSEGV 风险。"
    echo "    如需完整特性：选 v0.4.4 及以上版本重新构建。"
    echo "=============================================================="
  fi
  local _sym_ok=1
  for _sym in shmget shmat shmctl shmdt getDB FreeDynablock; do
    if [[ "${_sym}" == shm* && "${_patch_in_tree}" == "0" ]]; then
      continue   # 补丁不在链：shm 符号必缺，跳过（见上）
    fi
    if ! nm "${_src}/build/box64" 2>/dev/null | grep -qw "${_sym}" \
       && ! readelf -Ws "${_src}/build/box64" 2>/dev/null | grep -qw "${_sym}"; then
      echo "错误: box64 符号表缺 ${_sym}（v3.2.18 内建 shm 实现 / DYNAREC 核心未链接 —— 0001 补丁没生效？）"
      _sym_ok=0
    fi
  done
  if [[ "${_patch_in_tree}" == "1" ]] \
     && ! nm -D "${_src}/build/box64" 2>/dev/null | grep -qwE "shmget|my_shmget"; then
    echo "错误: shm 符号未进动态导出表（.dynsym），wrapped-libc 桥接将找不到 my_*"
    echo "  （v3.2.21 根因：上游 -fvisibility=hidden 隐藏符号永不入 .dynsym，且 my_* 无"
    echo "   静态引用会被 --gc-sections 回收 —— 查 0001 补丁 ANW_EXPORT 可见性修复是否在链）"
    _sym_ok=0
  fi
  local _shmem_ok=1
  if [[ "${_patch_in_tree}" == "1" ]] \
     && [[ ! -f "${_src}/build/CMakeFiles/mainobj.dir/src/wrapped/wrappedandroidshmem.c.o" ]]; then
    echo "错误: wrappedandroidshmem.c 编译单元不在链（-DANDROID=ON 未生效？）"
    _shmem_ok=0
  fi
  if ! strings "${_src}/build/box64" 2>/dev/null | grep -q "libandroid-shmem\.so"; then
    # 上游 ANDROID 模式自带 wrappedandroidshmem 注册（不依赖 0001 补丁），照常硬校验
    echo "错误: 产物未注册 libandroid-shmem.so 包装（ANDROID 编译单元缺失）"
    _shmem_ok=0
  fi
  [[ "${_sym_ok}" == "1" && "${_shmem_ok}" == "1" ]] \
    && echo "冒烟[1/2]: $(
      if [[ "${_patch_in_tree}" == "1" ]]; then
        echo "内建 SysV shm（v3.2.21 默认可见性导出：shmget/shmat/shmctl/shmdt + my_* 均已入 .dynsym）+ "
      else
        echo "（0001 补丁未在链，shm 断言跳过；见上方警告） "
      fi
    )DYNAREC 核心（getDB/FreeDynablock）✓" \
    || return 1
  if grep -q "image-base\|no-pie" "${_src}/build/CMakeFiles/box64.dir/link.txt" 2>/dev/null; then
    echo "错误: 链接命令残留 --image-base/-no-pie（NOLOADADDR 未生效）"
    grep -h "image-base\|no-pie" "${_src}/build/CMakeFiles/box64.dir/link.txt"
    return 1
  fi
  echo "冒烟[2/2]: 无 --image-base/-no-pie（NOLOADADDR 已生效）✓"

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
