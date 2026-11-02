revision="1.5.0"
url="https://github.com/xiph/flac.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
#args="-DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DFLAC_BUILD_EXAMPLES=OFF -DFLAC_BUILD_TESTS=OFF -DFLAC_BUILD_DOCS=OFF -DOGG_FOUND=ON -DOGG_INCLUDE_DIR=${prefix}/include -DOGG_LIBRARY=${prefix}/lib/libogg.so -DINSTALL_MANPAGES=OFF"
deps="libogg"

# ===== backup: original autotools build =====
buildSys="autotools"