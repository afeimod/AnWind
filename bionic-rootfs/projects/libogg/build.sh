revision="1.3.6"
url="https://downloads.xiph.org/releases/ogg/libogg-${revision}.tar.gz"
# v3.3.42 修正（CI 实证：x86_64 / arm64ec 两条 wine 构建全部折在这里）——
#   1) downloads.xiph.org 会 302 跳到 ftp.osuosl.org；CI runner 到该跳板的
#      TLS 经常被中断（"OpenSSL: error:0A000126: unexpected eof while
#      reading" / "No data received"）→ 直接把 osuosl 主站写成兜底源，
#      绕开那一跳；
#   2) 原 Debian 兜底 URL 扩展名写错（.tar.gz）—— Debian pool 里 libogg
#      1.3.6 实际是 **libogg_1.3.6.orig.tar.xz**（pool 实测：只有
#      1.3.4.tar.gz / 1.3.5.tar.xz / 1.3.6.tar.xz），所以那个兜底从来
#      没生效过（404）→ 改成真实的 .tar.xz 文件名。
#   框架侧（build-rootfs-arch.sh 的 dl_src）也会把 downloads.xiph.org
#   自动展开出 ftp.osuosl.org 镜像，三重保险。
backupUrls=(
  "https://ftp.osuosl.org/pub/xiph/releases/ogg/libogg-${revision}.tar.gz"
  "http://deb.debian.org/debian/pool/main/libo/libogg/libogg_${revision}.orig.tar.xz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
args="-DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DBUILD_TESTING=OFF"

# ===== backup: original autotools build =====
# buildSys="autotools"
