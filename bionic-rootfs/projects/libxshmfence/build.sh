revision="1.3.3"
url="https://xorg.freedesktop.org/archive/individual/lib/libxshmfence-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# 共享内存栅栏（mesa DRI3/GLX 必需）。
# --disable-futex 与 Termux x11-packages 保持一致：pthread 后端的栅栏内存
# 布局与 AnWind 内置 X 服务（termux-x11 / libXlorie）侧一致，否则 DRI3
# fence fd 传递时两端 ABI 不兼容。分配走 memfd_create 裸系统调用，bionic 可用。
deps="xorgproto"

args="
  --disable-futex
"
