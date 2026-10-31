revision="noble"
urlType="others"
arch="aarch64"
buildSys="others"
license="LGPL-2.1+"
pkgSrcDir="${wsDir}/projects/glibc-aarch64"

# ============================================================
# box64 用的 aarch64 glibc 运行库 + 私有加载器
# （usr/lib/aarch64-linux-gnu，含 ld-linux-aarch64.so.1）
# ------------------------------------------------------------
# 【设备实测根因】aarch64 rootfs 的 box64 需要自己的
# ld-linux-aarch64.so.1 —— 此前 rootfs 里根本没有，box64 也
# 没有 patchelf 指向它。本配方在 bionic-rootfs 构建体系内
# 补齐这一层（与既有 glibc-x86_64 配方同一思路，不引入任何
# 体系外的 glibc 环境）：
#   * 从 Ubuntu noble【arm64】的 libc6 / libgcc-s1 / libstdc++6 /
#     zlib1g 四个核心 deb 取运行库，平铺安装到
#       ${prefix}/lib/aarch64-linux-gnu/
#     其中 ld-linux-aarch64.so.1 是 box64 配方 patchelf
#     --set-interpreter 的钉死目标
#   * bionic 加载器（linker64）永不搜索该目录，与 rootfs 自身
#     库零串扰；只有被 patchelf 到私有加载器上的 glibc 程序
#     （box64）使用它
#   * 编译侧对齐：box64 配方用宿主交叉工具链
#     aarch64-linux-gnu-gcc 构建（noble 同源 glibc 2.39），
#     编译期与运行期版本一致
# 仅 aarch64 rootfs 需要（x86_64 设备原生运行，无需模拟层）。
#
# 版本发现（防 pool 漂移）：
#   arm64 二进制不在 archive.ubuntu.com（那里只有 amd64/i386），
#   在 ports.ubuntu.com/ubuntu-ports。旧版 glibc-x86_64 配方把
#   版本号写死在 URL 里，pool 清理旧修订后即 404（其注释自证：
#   "1.3.dfsg-3.1ubuntu2.2 已 404，导致 CI 整轮报废"）。本配方
#   改为解析 noble 套件索引（noble-updates → noble-security →
#   noble 逐级查找，dpkg 比较取最高版本）自动锁定当前存活
#   版本，不随上游清理报废。
# 环境变量覆盖（需钉死版本时用）：
#   ANWIND_PORTS_URL       镜像根（默认 http://ports.ubuntu.com/ubuntu-ports）
#   ANWIND_GLIBC_AARCH64_URL / ANWIND_GCC_AARCH64_URL /
#   ANWIND_ZLIB_AARCH64_URL 直接指定 deb 完整 URL（优先级最高，
#   适合内网镜像或离线场景）
# ============================================================

PORTS_URL="${ANWIND_PORTS_URL:-http://ports.ubuntu.com/ubuntu-ports}"
POCKETS="${ANWIND_AARCH64_POCKETS:-noble-updates noble-security noble}"

# 目标包（Debian 源码包 → 运行库 deb）
PKG_LIBC6="libc6"
PKG_LIBGCC="libgcc-s1"
PKG_LIBSTDCXX="libstdc++6"
PKG_ZLIB="zlib1g"

custom_url() {
  # 必须用绝对路径：get_src 在 cd "${srcDir}" 后调用本函数，而
  # extra_fuction 执行时构建系统已 cd 进 pkgSrcDir（源码树目录），
  # 相对路径在两个阶段指向不同位置 -> 下载成功但校验报"缺少已下载
  # 的包"（glibc-x86_64 配方 CI run#14 的老坑，此处照抄防护）。
  local _debDir="${srcDir}/${pjName}"
  local _idxDir="${_debDir}/pkgindex"
  local _found

  mkdir -p "${_debDir}" "${_idxDir}"

  # ---- 逐包解析存活版本（URL 覆盖优先） ----
  # resolve_pkg <包名>：从套件索引解析，stdout 输出 "版本|相对路径"，
  # 三个 pocket 中取 dpkg 版本最高者；全部落空时 stderr 报错并 return 1
  # （注意：本函数在命令替换子 shell 里运行，错误必须走 stderr、
  # 失败必须用返回码——子 shell 里的 exit 1 不会中止父进程）
  resolve_pkg() {
    local _want="$1"
    local _bestV="" _bestF="" _v _f _line _pocket
    for _pocket in ${POCKETS}; do
      # 索引每轮构建只拉一次：get_src 进入前会 rm -rf ${srcDir}/${pjName}，
      # 整个下载目录每轮都是全新的，这里的“存在即复用”只在本次
      # custom_url 的 4 次解析之间生效（12 次下载缩为 3 次），
      # 不存在跨构建的陈旧索引问题
      if [[ ! -s "${_idxDir}/Packages-${_pocket}.gz" ]]; then
        wget -nv --timeout=90 --tries=2 --waitretry=3 \
          -O "${_idxDir}/.tmp-${_pocket}.gz" \
          "${PORTS_URL}/dists/${_pocket}/main/binary-arm64/Packages.gz" \
          || { echo "警告: 套件索引下载失败 => ${_pocket}" >&2; continue; }
        mv -f "${_idxDir}/.tmp-${_pocket}.gz" "${_idxDir}/Packages-${_pocket}.gz"
      fi
      _line="$(zcat "${_idxDir}/Packages-${_pocket}.gz" 2>/dev/null | awk -v _want="$_want" '
        /^Package: / { p=$2 }
        /^Version: / { v=$2 }
        /^Filename: / { f=$2 }
        /^$/ { if (p==_want) print v "|" f; p=v=f="" }
        END { if (p!="" && p==_want) print v "|" f }' | tail -n 1)"
      [[ -n "${_line}" ]] || continue
      _v="${_line%%|*}"
      _f="${_line#*|}"
      if [[ -z "${_bestV}" ]] || dpkg --compare-versions "${_v}" gt "${_bestV}"; then
        _bestV="${_v}"
        _bestF="${_f}"
      fi
    done
    if [[ -z "${_bestF}" ]]; then
      echo "错误: 套件索引中找不到 ${_want}（arm64/main，口袋: ${POCKETS}）" >&2
      return 1
    fi
    echo "${_bestV}|${_bestF}"
  }

  DEB_PATHS=()
  DEB_NAMES=()

  # URL 直覆盖（不走索引发现）
  if [[ -n "${ANWIND_GLIBC_AARCH64_URL:-}" ]]; then
    DEB_PATHS+=("${ANWIND_GLIBC_AARCH64_URL}")
  else
    _found="$(resolve_pkg "${PKG_LIBC6}")" \
      || { echo "错误: 解析 libc6 版本失败（索引不可达或包被移除）" && exit 1; }
    echo "libc6 => ${_found%%|*}"
    DEB_PATHS+=("${PORTS_URL}/${_found#*|}")
  fi
  if [[ -n "${ANWIND_GCC_AARCH64_URL:-}" ]]; then
    DEB_PATHS+=("${ANWIND_GCC_AARCH64_URL}")
  else
    _found="$(resolve_pkg "${PKG_LIBGCC}")" \
      || { echo "错误: 解析 libgcc-s1 版本失败" && exit 1; }
    echo "libgcc-s1 => ${_found%%|*}"
    DEB_PATHS+=("${PORTS_URL}/${_found#*|}")
    _found="$(resolve_pkg "${PKG_LIBSTDCXX}")" \
      || { echo "错误: 解析 libstdc++6 版本失败" && exit 1; }
    echo "libstdc++6 => ${_found%%|*}"
    DEB_PATHS+=("${PORTS_URL}/${_found#*|}")
  fi
  if [[ -n "${ANWIND_ZLIB_AARCH64_URL:-}" ]]; then
    DEB_PATHS+=("${ANWIND_ZLIB_AARCH64_URL}")
  else
    _found="$(resolve_pkg "${PKG_ZLIB}")" \
      || { echo "错误: 解析 zlib1g 版本失败" && exit 1; }
    echo "zlib1g => ${_found%%|*}"
    DEB_PATHS+=("${PORTS_URL}/${_found#*|}")
  fi

  # ---- 下载 ----
  local _deb _name
  for _deb in "${DEB_PATHS[@]}"; do
    _name="$(basename "${_deb}")"
    echo "下载 => ${_name}"
    wget -nv --timeout=60 --tries=2 --waitretry=3 \
      -O "${_debDir}/${_name}" "${_deb}" \
      || { echo "下载失败 => ${_deb}" && exit 1; }
    DEB_NAMES+=("${_name}")
  done
}

extra_fuction() {
  local _destLib="${destDir}${prefix}/lib/aarch64-linux-gnu"
  local _debDir="${srcDir}/${pjName}"   # custom_url 的绝对下载目录，见上
  local _deb _name _ext

  mkdir -p "${_destLib}"

  for _deb in "${DEB_PATHS[@]}"; do
    _name="$(basename "${_deb}")"
    _ext="${pjName}/deb-${_name%.deb}"

    if [[ ! -f "${_debDir}/${_name}" ]]; then
      echo "错误: 缺少已下载的包 => ${_debDir}/${_name}" && exit 1
    fi
    rm -rf "${_ext}"
    mkdir -p "${_ext}"
    dpkg-deb -x "${_debDir}/${_name}" "${_ext}" \
      || { echo "deb 解包失败 => ${_name}" && exit 1; }

    # 平铺 usr/lib/aarch64-linux-gnu/*（libc/libm/libpthread/libdl/
    # librt/ld-linux-aarch64.so.1/libstdc++/libgcc_s/libz 及其符号
    # 链接；noble arm64 为 usrmerge 布局，实体都在该目录）
    if [[ -d "${_ext}/usr/lib/aarch64-linux-gnu" ]]; then
      cp -a "${_ext}/usr/lib/aarch64-linux-gnu/." "${_destLib}/"
    fi
    # 兼容旧布局扫描（noble 实测无此目录，防上游回退）
    [[ -d "${_ext}/lib/aarch64-linux-gnu" ]] && \
      cp -a "${_ext}/lib/aarch64-linux-gnu/." "${_destLib}/"
  done

  # ---- 安装校验：加载器与核心运行库必须齐 ----
  # ld-linux-aarch64.so.1 列首位——它是 box64 patchelf 的钉死目标，
  # 缺它整条 glibc-box64 链路都不成立。
  local _f _miss=0
  for _f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libdl.so.2 \
            libpthread.so.0 librt.so.1 libstdc++.so.6 libgcc_s.so.1 libz.so.1; do
    if [[ ! -e "${_destLib}/${_f}" ]]; then
      echo "错误: aarch64-linux-gnu 缺少 ${_f}"
      _miss=1
    fi
  done
  [[ "$_miss" == "0" ]] || { echo "安装内容:" && ls "${_destLib}" | head -30 && exit 1; }

  # glibc 2.34+ 把 libpthread/libdl/librt 并入 libc（留占位兼容旧
  # 二进制），noble 的 deb 自带这三个占位 .so —— 上面校验已覆盖。
  echo "glibc-aarch64 安装完成: $(ls "${_destLib}" | wc -l) 项, $(du -sh "${_destLib}" | cut -f1)"
  echo "私有加载器: ${prefix}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
}
