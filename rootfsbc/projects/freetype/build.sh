revision="VER-2-14-3"
url="https://github.com/freetype/freetype.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="FTL-1.1 OR GPL-2.0-or-later"
# freetype 与 harfbuzz 互相依赖，禁用可选方向避免循环依赖（同 termux-packages 的做法）；
# 且 prefix 中会残留上次构建的 harfbuzz.pc，auto 可能误探测导致链接失败
args="
  -Dbrotli=enabled
  -Dbzip2=enabled
  -Dharfbuzz=disabled
  -Dmmap=enabled
  -Dpng=enabled
  -Dtests=disabled
  -Dzlib=enabled
"
deps="brotli bzip2 libpng zlib"