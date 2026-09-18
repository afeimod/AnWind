revision="1.2.3"
url="https://xorg.freedesktop.org/archive/individual/lib/libXcursor-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# 光标库；patch1 将图标/光标主题搜索路径改到 rootfs 前缀
deps="libX11 libXfixes libXrender xorgproto"
