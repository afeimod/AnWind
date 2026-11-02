revision="2026c-1"
# 不需要区分架构
url="http://de.mirror.archlinuxarm.org/aarch64/core/tzdata-${revision}-aarch64.pkg.tar.xz"
urlType="wget"
license="public-domain"
arch="aarch64 x86_64"
buildSys="others"
extra_fuction() {
  bsdtar -xvf /tmp/download-src/tzdata-${revision}-aarch64.pkg.tar.xz \
    --strip-components=1 \
    --include='usr/share/zoneinfo/*' \
    -C "${destDir}/${prefix}" || { echo "解压失败!" && exit 1;}
}
