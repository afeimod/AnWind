# https://gmplib.org/download/gmp/gmp-6.3.0.tar.xz
revision="6.3.0"
url="https://gmplib.org/download/gmp/gmp-${revision}.tar.xz"
urlType="tar"
buildSys="autotools"
arch="aarch64 x86_64"
license="LGPL-3.0-or-later"
args="
  --enable-cxx
  --without-readline
"
deps="libc++"
pre_setup() {
  export CXXFLAGS+=" -L${prefix}/lib -Wl,-rpath=${prefix}/lib"
}