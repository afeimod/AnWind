revision="1.3.6"
url="https://downloads.xiph.org/releases/ogg/libogg-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
args="-DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DBUILD_TESTING=OFF"

# ===== backup: original autotools build =====
# buildSys="autotools"