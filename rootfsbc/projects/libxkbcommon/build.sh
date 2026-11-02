revision="xkbcommon-1.9.2"
url="https://github.com/xkbcommon/libxkbcommon.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
# enable-wayland 必须关：xkbcli 调试工具用 pkg-config 找 wayland-protocols
# 和 wayland-scanner.pc（build 机依赖），host_tools 里的 scanner 二进制对它
# 不可见，meson.build 是硬性 ERROR 无自动降级；wine 只需要库本体
args="
  -Denable-docs=false
  -Denable-wayland=false
  -Denable-x11=false
"
deps="libxml2"
