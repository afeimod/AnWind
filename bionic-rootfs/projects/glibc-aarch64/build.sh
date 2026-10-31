revision="2.39-0ubuntu8.9"
urlType="others"
arch="aarch64"
buildSys="others"
license="LGPL-2.1+"
pkgSrcDir="${wsDir}/projects/glibc-aarch64"

# ============================================================
# bionic-box64 用的 aarch64 glibc 运行库（usr/lib/aarch64-linux-gnu）
# ------------------------------------------------------------
# 背景（设备日志 wine-9.2-1 实证）：桌面 wine 容器的 box64 是
# glibc 链接形态（终端 box64 同款 WINLATOR_GLIBC 模型，见
# projects/box64/build.sh），其 PT_INTERP 由 patchelf 钉到
#   /data/data/com.anwind/files/rootfs/usr/lib/aarch64-linux-gnu/
#   ld-linux-aarch64.so.1
# 该加载器与配套 glibc 运行库【必须随 bionic-rootfs 构建体系产
# 出并安装进 rootfs】，否则 box64 在设备上直接段错误（退出码
# 139、零输出）—— 此前 rootfs 缺整个 aarch64-linux-gnu 目录。
#
# 与 glibc-x86_64 配方（仓库既有、已随 rootfs 交付验证）完全同构：
#   * glibc-x86_64 面向"box64 仿真的 x86_64 glibc 程序"
#     （usr/lib/x86_64-linux-gnu，仿真侧加载器）
#   * 本配方面向"box64 自身的宿主侧"（usr/lib/aarch64-linux-gnu，
#     PT_INTERP 由内核直接加载，真机原生执行）
#   两个目录只有 box64 会用（rootfs 自身库全部 bionic 链接、走
#   Android linker64，永不搜索这两个目录），零串扰。
#
# 实现（glibc-x86_64 同款思路，位置对齐 Debian 布局）：
#   * 从 Ubuntu noble（glibc 2.39，与 ubuntu-24.04 CI 宿主的
#     aarch64-linux-gnu-gcc 交叉工具链同源，ABI 严格一致）的
#     ports.ubuntu.com 抓取 libc6 / libgcc-s1 / libstdc++6 /
#     zlib1g 四个核心 deb（arm64）
#   * 版本优先从 noble 套件索引（Packages.gz）自动发现存活版本，
#     防 Ubuntu pool 清理旧修订导致 404 报废整轮 CI（glibc-x86_64
#     配方已吃过 zlib 1.3.dfsg-3.1ubuntu2.2 被 404 的亏）；
#     可用环境变量钉死版本绕过发现（ANWIND_GLIBC_VER 等）
#   * 解包后把 usr/lib/aarch64-linux-gnu/* 平铺安装到
#       $prefix/lib/aarch64-linux-gnu/
#     （含 ld-linux-aarch64.so.1；usrmerge 后实体就在该目录）
# 仅 aarch64 rootfs 需要（x86_64 设备原生运行 wine，无 box64）。
# ============================================================

UBUNTU_PORTS="http://ports.ubuntu.com/ubuntu-ports"
# 套件优先级：updates > security > 基础 noble（新修订通常在 updates/security）
SUITES="noble-updates noble-security noble"

# 版本钉死（可选）：设置任一 ANWIND_*_VER 即跳过索引发现，直接用
# 指定版本构造 pool URL。未设置时从 Packages.gz 自动发现存活版本。
GLIBC_VER="${ANWIND_GLIBC_VER:-}"
GCC_VER="${ANWIND_GCC_VER:-}"
ZLIB_VER="${ANWIND_ZLIB_VER:-}"

# 索引不可用时的硬编码兜底版本（若届时 pool 也已清理，构建会在
# 下载步骤报错并提示用环境变量钉死存活版本）
GLIBC_FALLBACK="2.39-0ubuntu8.9"
GCC_FALLBACK="14.2.0-4ubuntu2~24.04.1"
ZLIB_FALLBACK="1.3.dfsg-3.1ubuntu2.3"

# deb 规格表：包名 | pool 子目录 | 版本环境变量名 | 硬编码兜底
# pool 路径规律：libc6 在 g/glibc/，libgcc-s1 与 libstdc++6 在
# g/gcc-14/（noble 的 gcc 主版本），zlib1g 在 z/zlib/
DEB_SPECS="
libc6|g/glibc|GLIBC_VER|2.39-0ubuntu8.9
libgcc-s1|g/gcc-14|GCC_VER|14.2.0-4ubuntu2~24.04.1
libstdc++6|g/gcc-14|GCC_VER|14.2.0-4ubuntu2~24.04.1
zlib1g|z/zlib|ZLIB_VER|1.3.dfsg-3.1ubuntu2.3
"

# 从套件索引里发现某个包当前存活的版本与 pool 文件名。
# 输出 "<version> <pool相对路径>"（单行）；找不到返回非 0。
discover_pkg() {
  local _pkg="$1" _suite _idx _hit
  for _suite in ${SUITES}; do
    _idx="${srcDir}/${pjName}/pkgindex/${_suite}.gz"
    [[ -f "${_idx}" ]] || continue
    _hit="$(zcat "${_idx}" 2>/dev/null | awk -v pkg="${_pkg}" '
      /^Package: /{ p = ($2 == pkg) }
      p && /^Version: /{ v = $2 }
      p && /^Filename: /{ f = $2; print v, f; p = 0 }
    ' | sort -V | tail -n 1)"
    [[ -n "${_hit}" ]] && { echo "${_hit}"; return 0; }
  done
  return 1
}

custom_url() {
  # 必须用绝对路径：get_src 在 cd "${srcDir}" 后调用本函数，而
  # extra_fuction 执行时构建系统已 cd 进 pkgSrcDir（源码树目录），
  # 相对路径在两个阶段指向不同位置（glibc-x86_64 配方注释同款坑）。
  local _spec _pkg _poolDir _verVar _fallback _ver _url _name
  local _urls=()
  mkdir -p "${srcDir}/${pjName}/pkgindex"

  # 下载套件索引（失败仅告警：版本发现是兜底能力，失败回退硬编码）
  for _spec in ${SUITES}; do
    wget -nv --timeout=60 --tries=2 --waitretry=3 \
      -O "${srcDir}/${pjName}/pkgindex/.tmp-${_spec}.gz" \
      "${UBUNTU_PORTS}/dists/${_spec}/main/binary-arm64/Packages.gz" \
      && mv "${srcDir}/${pjName}/pkgindex/.tmp-${_spec}.gz" \
            "${srcDir}/${pjName}/pkgindex/${_spec}.gz" \
      || echo "警告: 套件索引下载失败 => ${_spec}（将回退硬编码版本）"
  done

  while IFS='|' read -r _pkg _poolDir _verVar _fallback; do
    [[ -z "${_pkg}" ]] && continue
    if [[ -n "${!_verVar:-}" ]]; then
      # 环境变量钉死版本
      _ver="${!_verVar}"
      _name="${_pkg}_${_ver}_arm64.deb"
      _url="${UBUNTU_PORTS}/pool/main/${_poolDir}/${_name}"
      echo "${_pkg} => ${_ver}（环境变量钉死）"
    else
      local _disc
      if _disc="$(discover_pkg "${_pkg}")"; then
        # 套件索引发现的存活版本（Filename 字段即 pool 相对路径）
        _ver="${_disc%% *}"
        _url="${UBUNTU_PORTS}/${_disc#* }"
        _name="$(basename "${_url}")"
        echo "${_pkg} => ${_ver}（套件索引发现）"
      else
        # 索引不可用：硬编码兜底
        _ver="${_fallback}"
        _name="${_pkg}_${_ver}_arm64.deb"
        _url="${UBUNTU_PORTS}/pool/main/${_poolDir}/${_name}"
        echo "${_pkg} => ${_ver}（索引不可用，硬编码兜底）"
      fi
    fi
    _urls+=("${_url}")
    echo "下载 => ${_name}"
  done <<< "${DEB_SPECS}"

  local _u
  for _u in "${_urls[@]}"; do
    wget -nv --timeout=60 --tries=2 --waitretry=3 \
      -O "${srcDir}/${pjName}/$(basename "${_u}")" "${_u}" \
      || { echo "下载失败 => ${_u}（Ubuntu pool 可能已清理该版本，请用 ANWIND_*_VER 环境变量钉死存活版本）" && exit 1; }
  done
}

extra_fuction() {
  local _destLib="${destDir}${prefix}/lib/aarch64-linux-gnu"
  local _debDir="${srcDir}/${pjName}"   # custom_url 的绝对下载目录，见上
  local _pkg _poolDir _verVar _fallback _path _name _ext

  mkdir -p "${_destLib}"

  while IFS='|' read -r _pkg _poolDir _verVar _fallback; do
    [[ -z "${_pkg}" ]] && continue
    # 下载目录里的实际文件（钉死/发现/兜底三种来源都落在这里；
    # 同名多版本时取最新）
    _path="$(ls "${_debDir}/${_pkg}_"*_arm64.deb 2>/dev/null | sort -V | tail -n 1)"
    [[ -n "${_path}" ]] || { echo "错误: 缺少已下载的包 => ${_debDir}/${_pkg}_*_arm64.deb" && exit 1; }
    _name="$(basename "${_path}")"
    # 解包到 srcDir 暂存区（.gitignore 已忽略 /src/），必须用绝对路径：
    # 本函数执行时 cwd 已是 pkgSrcDir（projects/ 下的仓库目录），相对
    # 路径会把 300+ 解包文件直接污染进 git 工作区
    _ext="${_debDir}/deb-${_name%.deb}"

    rm -rf "${_ext}"
    mkdir -p "${_ext}"
    dpkg-deb -x "${_debDir}/${_name}" "${_ext}" \
      || { echo "deb 解包失败 => ${_name}" && exit 1; }

    # 平铺 usr/lib/aarch64-linux-gnu/*（libc/libm/libpthread/ld-linux/
    # libstdc++/libgcc_s/libz 及其符号链接；Ubuntu usrmerge 后
    # ld-linux-aarch64.so.1 实体就在该目录）
    if [[ -d "${_ext}/usr/lib/aarch64-linux-gnu" ]]; then
      cp -a "${_ext}/usr/lib/aarch64-linux-gnu/." "${_destLib}/"
    fi
    # 兼容旧布局：lib/aarch64-linux-gnu 也扫一遍（若有）
    [[ -d "${_ext}/lib/aarch64-linux-gnu" ]] && \
      cp -a "${_ext}/lib/aarch64-linux-gnu/." "${_destLib}/"
  done <<< "${DEB_SPECS}"

  # ---- 安装校验：box64 宿主侧核心文件必须齐 ----
  # ld-linux-aarch64.so.1 是 box64 的 PT_INTERP（patchelf 钉死目标），
  # 其余是 box64 自身 DT_NEEDED（noble glibc 2.39 把 libpthread/
  # libdl/librt 并入 libc，占位 .so 随包自带）。
  local _f _miss=0
  for _f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libdl.so.2 \
            libpthread.so.0 librt.so.1 libstdc++.so.6 libgcc_s.so.1 libz.so.1; do
    if [[ ! -e "${_destLib}/${_f}" ]]; then
      echo "错误: aarch64-linux-gnu 缺少 ${_f}"
      _miss=1
    fi
  done
  [[ "$_miss" == "0" ]] || { echo "安装内容:" && ls "${_destLib}" | head -30 && exit 1; }

  echo "glibc-aarch64 安装完成: $(ls "${_destLib}" | wc -l) 项, $(du -sh "${_destLib}" | cut -f1)"
  echo "box64 私有加载器: ${_destLib}/ld-linux-aarch64.so.1"
}
