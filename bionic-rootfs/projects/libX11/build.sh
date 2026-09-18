revision="1.8.11"
url="https://xorg.freedesktop.org/archive/individual/lib/libX11-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# X11 核心客户端库 + libX11-xcb（mesa x11 平台依赖）
# X 传输层由 xtrans 提供（已补丁对齐 AnWind 内置 X 服务的 socket 路径）
deps="pthread-stub xtrans libxcb xorgproto"

args="
  --disable-xf86bigfont
  --without-xmlto
  --without-fop
  --without-xsltproc
"
