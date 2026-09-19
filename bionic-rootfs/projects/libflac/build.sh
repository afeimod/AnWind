revision="1.5.0"
url="https://github.com/xiph/flac.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
# 说明：本配方曾以 buildSys="autotools" 兜底，但 git 源码无预生成 configure，
# 只能跑 autogen.sh，而 ubuntu-24.04 runner 的 PATH 中无 libtool/libtoolize，
# 导致 wine 与 rootfs 两条流水线双双死于 "Missing program 'libtool'"。
# FLAC 1.5.0 官方首选 CMake 构建（与 libogg 同路），FindOgg 支持显式传入
# OGG_INCLUDE_DIR / OGG_LIBRARY，无需依赖 pkg-config。
args="
  -DBUILD_SHARED_LIBS=ON
  -DBUILD_CXXLIBS=OFF
  -DBUILD_PROGRAMS=OFF
  -DBUILD_EXAMPLES=OFF
  -DBUILD_TESTING=OFF
  -DBUILD_DOCS=OFF
  -DINSTALL_MANPAGES=OFF
  -DWITH_OGG=ON
  -DOGG_INCLUDE_DIR=${prefix}/include
  -DOGG_LIBRARY=${prefix}/lib/libogg.so
"
deps="libogg"
