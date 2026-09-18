revision="1.5.4"
url="https://xorg.freedesktop.org/archive/individual/lib/libXrandr-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# 显示分辨率/刷新率切换（wine 分辨率模拟、vulkan xlib_xrandr WSI 依赖）
deps="libX11 libXext libXrender libXfixes xorgproto"
