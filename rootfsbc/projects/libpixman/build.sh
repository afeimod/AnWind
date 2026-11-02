revision="0.46.4"
url="https://cairographics.org/releases/pixman-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
args="
  -Dlibpng=disabled
  -Dtests=disabled
"
deps="zlib"
