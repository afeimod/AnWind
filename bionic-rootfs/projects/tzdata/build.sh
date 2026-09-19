revision="2026c-1"
# 不需要区分架构
# 源：Debian pool（tzdata 为 _all.deb，含全平台 zoneinfo；pool 永久保留，
# 不受 Arch ARM 滚动镜像影响 —— 原 de.mirror.archlinuxarm.org 源已 404）
url="http://deb.debian.org/debian/pool/main/t/tzdata/tzdata_${revision}_all.deb"
urlType="wget"
license="public-domain"
arch="aarch64 x86_64"
buildSys="others"
extra_fuction() {
  # .deb = ar 归档（binutils 的 ar 为 runner 自带，不依赖 bsdtar）
  local _deb="/tmp/download-src/tzdata_${revision}_all.deb"
  local _tmpDir="/tmp/download-src/tzdata-extract"
  rm -rf "${_tmpDir}" && mkdir -p "${_tmpDir}"
  ar p "${_deb}" data.tar.xz > "${_tmpDir}/data.tar.xz" || { echo "读取 data.tar.xz 失败!" && exit 1; }
  # 修复：Debian deb 的 data.tar.xz 成员路径带 ./ 前缀（./usr/share/zoneinfo/...），
  # 旧模式 'usr/share/zoneinfo/*' 匹配不到任何成员，tar 报
  # "usr/share/zoneinfo/*: Not found in archive" 直接失败；
  # 模式必须带 ./ 前缀才能命中（fix: leading ./ in wildcard pattern）。
  tar -xJf "${_tmpDir}/data.tar.xz" -C "${_tmpDir}" \
    --wildcards './usr/share/zoneinfo/*' || { echo "解压失败!" && exit 1; }
  # 修复：zoneinfo 安装回 ${prefix}/share/zoneinfo（与 Arch 源时代一致的单层 usr，
  # 旧代码误写成 ${prefix}/usr/share/zoneinfo，会在 rootfs 里多出一层 usr/usr）
  mkdir -p "${destDir}${prefix}/share/zoneinfo"
  cp -a "${_tmpDir}/usr/share/zoneinfo/." "${destDir}${prefix}/share/zoneinfo/" \
    || { echo "安装 zoneinfo 失败!" && exit 1; }
  rm -rf "${_tmpDir}"
}
