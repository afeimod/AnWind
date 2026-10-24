revision="1.33.7"
url="https://mpg123.de/download/mpg123-${revision}.tar.bz2"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="LGPL-2.1 GPL-2.0"
args="
  --disable-components 
  --enable-libmpg123 
  --enable-libout123 
  --enable-libsyn12
"