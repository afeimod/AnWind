revision="2.39-0ubuntu8.9"
urlType="others"
arch="aarch64"
buildSys="others"
license="LGPL-2.1+"
pkgSrcDir="${wsDir}/projects/glibc-aarch64"

# ============================================================
# box64 本体用的 aarch64 glibc 运行库（usr/lib/aarch64-linux-gnu）
# ------------------------------------------------------------
# 背景（v3.2.5，设备实测退出码 139/127 的根因修复）：
#   AnWind 的 box64 构建从 bionic 形态（-DANDROID=ON，NDK clang）
#   切换到 glibc 形态（Winlator/MiceWine 同款 -DWINLATOR_GLIBC=ON，
#   aarch64-linux-gnu-gcc 交叉构建）—— bionic 形态在真实 wine 负载
#   下 SIGSEGV（设备日志：冒烟测试 wine --version 可过，启动
#   explorer ... wineboot -i 秒退 139，零输出）。
#   glibc 形态 box64 是 glibc 链接的 aarch64 ELF，运行时需要：
#     1) PT_INTERP 加载器 ld-linux-aarch64.so.1（Android/bionic 没有）
#     2) libc.so.6 / libm / libdl / libpthread / librt / libgcc_s
#   —— 此前 rootfs 只为"box64 跑 x86_64 glibc 程序"准备过
#   glibc-x86_64（amd64 deb），aarch64 侧从未构建过，导致 glibc
#   box64 装上即"not found"（ENOENT）或依赖解不出来。
# 实现（与 glibc-x86_64 配方同思路，arm64 从 ports.ubuntu.com 取）：
#   * Ubuntu noble glibc 2.39（向后兼容旧二进制；arm64 包只在
#     ports.ubuntu.com —— archive.ubuntu.com 仅 amd64/i386 与
#     25.10+ 新系列的 arm64，noble arm64 旧修订在 archive 已 404）
#   * 解包后把 usr/lib/aarch64-linux-gnu/* 平铺安装到
#       $prefix/lib/aarch64-linux-gnu/
#     （含 ld-linux-aarch64.so.1；box64 配方在 install 阶段用
#     patchelf 把 PT_INTERP/RPATH 指到该目录的设备绝对路径，
#     并把整个目录随 box64 包再带一份 —— install-box64 免整包
#     重建 rootfs 即可升级）
#   * bionic 加载器永不搜索该目录（SONAME 带 .so.6/.so.1 版本号，
#     与 rootfs 的 bionic 无版本号 .so 零冲突）；box64 自身经
#     DT_RPATH 解析，不依赖 LD_LIBRARY_PATH。
# 仅 aarch64 rootfs 需要（x86_64 设备原生运行，无需模拟层）。
# 版本可用环境变量覆盖（Ubuntu pool 清旧修订时改 URL 即可）：
#   ANWIND_GLIBC_VER / ANWIND_GCC_VER / ANWIND_ZLIB_VER
# ============================================================

# ports.ubuntu.com：arm64/armhf 等"ports 架构"的包池（路径段为
# ubuntu-ports）；与 archive.ubuntu.com 同一源码包、同版本号。
UBUNTU_PORTS="http://ports.ubuntu.com/ubuntu-ports/pool/main"
GLIBC_VER="${ANWIND_GLIBC_VER:-2.39-0ubuntu8.9}"
GCC_VER="${ANWIND_GCC_VER:-14.2.0-4ubuntu2~24.04.1}"
# ⚠ Ubuntu pool 会清理旧修订（1.3.dfsg-3.1ubuntu2.2 已 404，导致 CI 整轮报废），
#   版本漂移时可用环境变量 ANWIND_ZLIB_VER 覆盖，无需改脚本。
ZLIB_VER="${ANWIND_ZLIB_VER:-1.3.dfsg-3.1ubuntu2.3}"

DEBS="
${UBUNTU_PORTS}/g/glibc/libc6_${GLIBC_VER}_arm64.deb
${UBUNTU_PORTS}/g/gcc-14/libgcc-s1_${GCC_VER}_arm64.deb
${UBUNTU_PORTS}/g/gcc-14/libstdc++6_${GCC_VER}_arm64.deb
${UBUNTU_PORTS}/z/zlib/zlib1g_${ZLIB_VER}_arm64.deb
"

custom_url() {
  # 必须用绝对路径：get_src 在 cd "${srcDir}" 后调用本函数，而
  # extra_fuction 执行时构建系统已 cd 进 pkgSrcDir（源码树目录），
  # 相对路径在两个阶段指向不同位置 -> 下载成功但校验报"缺少已下载的包"
  # （CI run#14 rootfs 构建折在此处；与 glibc-x86_64 同坑同解）。
  local _deb _name
  mkdir -p "${srcDir}/${pjName}"
  for _deb in $DEBS; do
    _name="$(basename "${_deb}")"
    echo "下载 => ${_name}"
    wget -nv --timeout=60 --tries=2 --waitretry=3 \
      -O "${srcDir}/${pjName}/${_name}" "${_deb}" \
      || { echo "下载失败 => ${_deb}" && exit 1; }
  done
}

extra_fuction() {
  local _destLib="${destDir}${prefix}/lib/aarch64-linux-gnu"
  local _debDir="${srcDir}/${pjName}"   # custom_url 的绝对下载目录，见上
  local _deb _name _ext

  mkdir -p "${_destLib}"

  for _deb in $DEBS; do
    _name="$(basename "${_deb}")"
    _ext="${pjName}/deb-${_name%.deb}"

    if [[ ! -f "${_debDir}/${_name}" ]]; then
      echo "错误: 缺少已下载的包 => ${_debDir}/${_name}" && exit 1
    fi
    rm -rf "${_ext}"
    mkdir -p "${_ext}"
    dpkg-deb -x "${_debDir}/${_name}" "${_ext}" \
      || { echo "deb 解包失败 => ${_name}" && exit 1; }

    # 平铺 usr/lib/aarch64-linux-gnu/*（libc/libm/libpthread/
    # ld-linux-aarch64/libstdc++/libgcc_s/libz 及其符号链接；
    # Ubuntu usrmerge 后 ld-linux-aarch64.so.1 实体也在该目录，
    # 实测 libc6_2.39-0ubuntu8.9_arm64.deb 解包即含）
    if [[ -d "${_ext}/usr/lib/aarch64-linux-gnu" ]]; then
      cp -a "${_ext}/usr/lib/aarch64-linux-gnu/." "${_destLib}/"
    fi
    # 兼容旧布局：lib/aarch64-linux-gnu 也扫一遍（若有）
    [[ -d "${_ext}/lib/aarch64-linux-gnu" ]] && \
      cp -a "${_ext}/lib/aarch64-linux-gnu/." "${_destLib}/"
  done

  # ---- 安装校验：核心文件必须齐（box64 PT_INTERP/RPATH 的硬依赖） ----
  local _f _miss=0
  for _f in libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 \
            librt.so.1 ld-linux-aarch64.so.1 libstdc++.so.6 libgcc_s.so.1 libz.so.1; do
    if [[ ! -e "${_destLib}/${_f}" ]]; then
      echo "错误: aarch64-linux-gnu 缺少 ${_f}"
      _miss=1
    fi
  done
  [[ "$_miss" == "0" ]] || { echo "安装内容:" && ls "${_destLib}" | head -30 && exit 1; }

  # glibc 2.34+ 把 libpthread/libdl/librt 并入 libc（留占位兼容旧
  # 二进制），noble 的 deb 自带这三个占位 .so —— 上面校验已覆盖。
  echo "glibc-aarch64 安装完成: $(ls "${_destLib}" | wc -l) 项, $(du -sh "${_destLib}" | cut -f1)"
}
