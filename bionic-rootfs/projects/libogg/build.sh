revision="1.3.6"
url="https://downloads.xiph.org/releases/ogg/libogg-${revision}.tar.gz"
# downloads.xiph.org 历史上多次长时间不可用，Debian pool 兜底
backupUrls=(
  "http://deb.debian.org/debian/pool/main/libo/libogg/libogg_${revision}.orig.tar.gz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
args="-DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DBUILD_TESTING=OFF"

# ===== backup: original autotools build =====
# buildSys="autotools"