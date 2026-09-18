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
  tar -xJf "${_tmpDir}/data.tar.xz" -C "${_tmpDir}" \
    --wildcards 'usr/share/zoneinfo/*' || { echo "解压失败!" && exit 1; }
  mkdir -p "${destDir}${prefix}/usr/share/zoneinfo"
  cp -a "${_tmpDir}/usr/share/zoneinfo/." "${destDir}${prefix}/usr/share/zoneinfo/" \
    || { echo "安装 zoneinfo 失败!" && exit 1; }
  rm -rf "${_tmpDir}"
}
