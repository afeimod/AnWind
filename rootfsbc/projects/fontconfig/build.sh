revision="2.18.2"
url="https://gitlab.freedesktop.org/fontconfig/fontconfig.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
argsToAutogenSh="1"
args="
  --disable-nls
  --enable-iconv
  --enable-libxml2
  --disable-docbook
  --disable-docs
  --with-default-fonts="${prefix}/share/fonts"
  ac_cv_va_copy=C99
"
# --with-add-fonts=/path/to/fonts
# --with-default-fonts=/system/fonts
deps="libxml2 libiconv freetype"