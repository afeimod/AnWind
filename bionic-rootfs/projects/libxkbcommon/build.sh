revision="xkbcommon-1.9.2"
url="https://github.com/xkbcommon/libxkbcommon.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
# AnWind 使用 X11 显示：enable-x11 生成 libxkbcommon-x11（XKB over XCB），
# 供 GTK/Qt 等使用；wayland 相关保持关闭
args="
  -Denable-docs=false
  -Denable-wayland=false
  -Denable-x11=true
"
deps="libxml2 xorgproto libxcb libX11"
