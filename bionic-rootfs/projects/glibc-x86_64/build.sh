revision="${ANWIND_GLIBC_X86_64_REV:-2.39}"
urlType="others"
arch="aarch64"
buildSys="others"
license="LGPL-2.1+"
pkgSrcDir="${wsDir}/projects/glibc-x86_64"

# ============================================================
# box64 用的 x86_64 glibc 运行库（usr/lib/x86_64-linux-gnu）
# ------------------------------------------------------------
# 背景：本 rootfs 为 bionic 系统，wine 本体是 bionic 链接、box64
# 直接加载无需 glibc；但用户还会用 box64 跑"常规 x86_64 Linux
# 程序"（glibc 链接的 ELF），这类程序需要 x86_64 版 glibc 运行库。
# 此前 rootfs 缺少该目录，box64 跑 glibc 程序直接报
#   "libpthread/libdl/libc.so.6: cannot open shared object file"
# 实现（与 glibc-aarch64 同思路，amd64 包在 archive.ubuntu.com）：
#   * 从 Ubuntu noble（glibc 2.39，向后兼容旧二进制）的
#     noble-updates / noble-security / noble 三个 pocket 的
#     Packages.gz 索引解析 libc6 / libgcc-s1 / libstdc++6 / zlib1g
#     的最新版本与 pool 路径（Filename 字段）—— Ubuntu pool 会
#     清理旧修订（libc6 2.39-0ubuntu8.9 已 404，box64-build-log-
#     aarch64(10).zip 实证），索引解析可自愈版本漂移，无需改脚本
#   * 解包后把 usr/lib/x86_64-linux-gnu/* 平铺安装到
#       $prefix/lib/x86_64-linux-gnu/
#     （含 ld-linux-x86-64.so.2；box64 会在 BOX64_LD_LIBRARY_PATH
#       各目录中查找 PT_INTERP 的加载器）
#   * bionic 加载器永不搜索该目录（仅 box64 使用），与 rootfs 自身
#     库零串扰；anwind-wine 在检测到该目录时自动导出
#     BOX64_LD_LIBRARY_PATH。
#
# 版本钉死（不走索引解析）可用环境变量：
#   ANWIND_GLIBC_VER / ANWIND_GCC_VER / ANWIND_ZLIB_VER
#   （URL 按 g/glibc、g/gcc-14、z/zlib 的 pool 惯例拼装）
# 包名 revision 亦可用 ANWIND_GLIBC_X86_64_REV 覆盖（默认取
# glibc 上游版本号 2.39；本地缓存 pkgs/ 下同 revision 旧 tar 想强制
# 刷新时用 --rebuild）。
#
# 仅 aarch64 rootfs 需要（x86_64 设备原生 bionic 运行，无模拟层
# 需求）。box64 配方 deps 挂了本包：`--build box64` / `--build all`
# 都会先构建本层再构建 box64，保证"box64 完整文件集"（仿真器
# 本体 + 双 glibc 运行层 + 配套库）一次到位。
# ============================================================

UBUNTU_ARCHIVE="http://archive.ubuntu.com/ubuntu"
# v3.3.37 镜像池（同 glibc-aarch64：GH runner 瞬时网络抖动两连击即整轮报废，
# box64-build-log-aarch64(7).zip 实证）。archive 有镜像池，依次试全部镜像，
# 全部失败才判死；可用 ANWIND_UBUNTU_MIRRORS 覆盖。
UBUNTU_MIRRORS="${ANWIND_UBUNTU_MIRRORS:-http://archive.ubuntu.com/ubuntu http://azure.archive.ubuntu.com/ubuntu http://us.archive.ubuntu.com/ubuntu https://mirrors.edge.kernel.org/ubuntu}"
# 环境变量钉死值（为空则走 Packages.gz 索引解析）
GLIBC_VER_PIN="${ANWIND_GLIBC_VER:-}"
GCC_VER_PIN="${ANWIND_GCC_VER:-}"
ZLIB_VER_PIN="${ANWIND_ZLIB_VER:-}"

_glibcPkgs="libc6 libgcc-s1 libstdc++6 zlib1g"

# 下载 noble 三 pocket 的 amd64 索引并解析 <pkg> 的最新版本与
# pool 路径；输出两行："<完整版本>" 与 "<pool相对路径>"
_resolve_pkg() {
  local _pkg="$1"
  local _idxDir="${srcDir}/${pjName}/pkgindex"
  local _pocket _gz _cand
  mkdir -p "${_idxDir}"
  for _pocket in noble-updates noble-security noble; do
    _gz="${_idxDir}/${_pocket}.gz"
    if [[ ! -s "${_gz}" ]]; then
      echo "索引 => ${_pocket}" >&2
      # 索引下载也走镜像池（索引拿不到则解析全失败，比 deb 更前置）
      for _m in ${UBUNTU_MIRRORS}; do
        if wget -q --timeout=90 --tries=3 --waitretry=5 --retry-connrefused \
          -O "${_gz}.tmp" "${_m}/dists/${_pocket}/main/binary-amd64/Packages.gz"; then
          mv -f "${_gz}.tmp" "${_gz}"
          break
        fi
        rm -f "${_gz}.tmp"
        echo "  [!] 索引镜像失败，换下一个 => ${_m}（${_pocket}）" >&2
      done
      [[ -s "${_gz}" ]] || echo "警告: 索引下载失败 => ${_pocket}（全部镜像）" >&2
    fi
  done
  # 汇总候选三列："<去epoch版本><TAB><完整版本><TAB><pool路径>"，
  # 按第 1 列版本排序取最高（epoch 前缀如 "1:" 不参与版本比较）
  _cand="$(
    for _pocket in noble-updates noble-security noble; do
      _gz="${_idxDir}/${_pocket}.gz"
      [[ -s "${_gz}" ]] || continue
      zcat "${_gz}" | awk -v p="${_pkg}" '
        $1 == "Package:"  { name = $2 }
        $1 == "Version:"  { ver  = $2 }
        $1 == "Filename:" { if (name == p) print ver "\t" $2 }
      '
    done | awk -F'\t' '{ v = $1; sub(/^[0-9]+:/, "", v); print v "\t" $0 }' \
      | sort -t$'\t' -k1,1V | tail -n 1 | cut -f2,3
  )"
  [[ -n "${_cand}" ]] || return 1
  printf '%s\n%s\n' "$(printf '%s' "${_cand}" | cut -f1)" \
    "$(printf '%s' "${_cand}" | cut -f2)"
}

custom_url() {
  # 必须用绝对路径：get_src 在 cd "${srcDir}" 后调用本函数，而
  # extra_fuction 执行时构建系统已 cd 进 pkgSrcDir（源码树目录），
  # 相对路径在两个阶段指向不同位置（CI run#14 rootfs 构建折在此处）
  local _debDir="${srcDir}/${pjName}"
  local _pkg _ver _file _url _name _ok
  local _resolved _rver _rfile
  mkdir -p "${_debDir}"

  local _urls=()
  for _pkg in ${_glibcPkgs}; do
    case "${_pkg}" in
      libc6)                  _ver="${GLIBC_VER_PIN}" ;;
      libgcc-s1 | libstdc++6) _ver="${GCC_VER_PIN}" ;;
      zlib1g)                 _ver="${ZLIB_VER_PIN}" ;;
    esac
    if [[ -n "${_ver}" ]]; then
      # 环境变量钉死：按 pool 惯例拼路径（版本漂移时需自行维护覆盖值）
      case "${_pkg}" in
        libc6)      _file="pool/main/g/glibc/libc6_${_ver}_amd64.deb" ;;
        libgcc-s1)  _file="pool/main/g/gcc-14/libgcc-s1_${_ver}_amd64.deb" ;;
        libstdc++6) _file="pool/main/g/gcc-14/libstdc++6_${_ver}_amd64.deb" ;;
        zlib1g)     _file="pool/main/z/zlib/zlib1g_${_ver}_amd64.deb" ;;
      esac
      echo "${_pkg} => ${_ver}（环境变量钉定）"
    else
      # 索引解析最新版本（自愈 Ubuntu pool 清理旧修订导致的 404）
      _resolved="$(_resolve_pkg "${_pkg}")" \
        || { echo "错误: 索引中找不到包 => ${_pkg}" && return 1; }
      _rver="$(printf '%s\n' "${_resolved}" | sed -n '1p')"
      _rfile="$(printf '%s\n' "${_resolved}" | sed -n '2p')"
      # epoch 前缀不参与 deb 文件名（zlib 的 "1:1.3.dfsg…" → "1.3.dfsg…"）
      _ver="$(printf '%s' "${_rver}" | sed 's/^[0-9]*://')"
      _file="${_rfile}"
      echo "${_pkg} => ${_rver}"
    fi
    _urls+=("${UBUNTU_ARCHIVE}/${_file}")
  done

  for _url in "${_urls[@]}"; do
    _name="$(basename "${_url}")"
    # 剥掉原镜像前缀得到 /pool/main/... 相对路径（对池内全部镜像成立），
    # 依次试镜像池；单镜像 --tries=2 对瞬时超时不够用（日志 7 两连击即死）。
    _rel="/${_url#*/ubuntu/}"
    echo "下载 => ${_name}"
    _ok=0
    for _m in ${UBUNTU_MIRRORS}; do
      if wget -nv --timeout=90 --tries=3 --waitretry=5 --retry-connrefused \
        -O "${_debDir}/${_name}" "${_m}${_rel}"; then
        _ok=1; break
      fi
      echo "  [!] 镜像失败，换下一个 => ${_m}${_rel}" >&2
      rm -f "${_debDir}/${_name}"
    done
    [[ ${_ok} == 1 ]] \
      || { echo "下载失败 => ${_url}（全部镜像重试后仍失败——网络问题非代码问题，请直接重跑 workflow）" && return 1; }
  done
}

extra_fuction() {
  local _destLib="${destDir}${prefix}/lib/x86_64-linux-gnu"
  local _debDir="${srcDir}/${pjName}"   # custom_url 的绝对下载目录，见上
  local _deb _name _ext

  mkdir -p "${_destLib}"

  for _deb in "${_debDir}"/*.deb; do
    [[ -f "${_deb}" ]] || continue
    _name="$(basename "${_deb}")"
    _ext="${pjName}/deb-${_name%.deb}"

    rm -rf "${_ext}"
    mkdir -p "${_ext}"
    dpkg-deb -x "${_deb}" "${_ext}" \
      || { echo "deb 解包失败 => ${_name}" && return 1; }

    # 平铺 usr/lib/x86_64-linux-gnu/*（libc/libm/libpthread/ld-linux/
    # libstdc++/libgcc_s/libz 及其符号链接；Ubuntu usrmerge 后
    # ld-linux-x86-64.so.2 实体也在该目录）
    if [[ -d "${_ext}/usr/lib/x86_64-linux-gnu" ]]; then
      cp -a "${_ext}/usr/lib/x86_64-linux-gnu/." "${_destLib}/"
    fi
    # 兼容旧布局：lib/x86_64-linux-gnu 与 lib64 也扫一遍（若有）
    if [[ -d "${_ext}/lib/x86_64-linux-gnu" ]]; then
      cp -a "${_ext}/lib/x86_64-linux-gnu/." "${_destLib}/"
    fi
    if [[ -d "${_ext}/lib64" ]]; then
      # lib64 下是 ld-linux 的绝对符号链接（指向 /usr/lib64/...），
      # 解包后为断链——仅当目标实体缺失时才补拷实体名
      local _ld="${_destLib}/ld-linux-x86-64.so.2"
      if [[ ! -e "${_ld}" ]] && ls "${_ext}"/lib64/ld-linux-x86-64.so.* >/dev/null 2>&1; then
        echo "警告: ld-linux 实体缺失，尝试从 lib64 链接目标解析"
      fi
    fi
  done

  # ---- 安装校验：核心文件必须齐（缺一即失败，不出残废包） ----
  local _f _miss=0
  for _f in libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 \
            librt.so.1 ld-linux-x86-64.so.2 libstdc++.so.6 libgcc_s.so.1 libz.so.1; do
    if [[ ! -e "${_destLib}/${_f}" ]]; then
      echo "错误: x86_64-linux-gnu 缺少 ${_f}"
      _miss=1
    fi
  done
  if [[ "${_miss}" != "0" ]]; then
    echo "安装内容:" && ls "${_destLib}" | head -30
    return 1
  fi

  # ---- 架构校验：加载器必须是 x86-64 ELF（防止索引/覆盖值拉错架构） ----
  # 注意：readelf 的 x86-64 Machine 值为多词 "Advanced Micro Devices
  # X86-64"（aarch64 侧是单词 "AArch64"），须取整行匹配
  local _machine
  _machine="$(readelf -h "${_destLib}/ld-linux-x86-64.so.2" 2>/dev/null \
    | sed -n 's/^ *Machine: *//p')"
  if [[ "${_machine}" != "Advanced Micro Devices X86-64" ]]; then
    echo "错误: ld-linux-x86-64.so.2 非 x86-64 ELF（Machine: ${_machine:-未知}）"
    return 1
  fi

  # glibc 2.34+ 把 libpthread/libdl/librt 并入 libc（留占位兼容旧
  # 二进制），noble 的 deb 自带这三个占位 .so —— 上面校验已覆盖。
  echo "glibc-x86_64 安装完成: $(ls "${_destLib}" | wc -l) 项, $(du -sh "${_destLib}" | cut -f1)"
}
