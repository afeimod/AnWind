revision="v17.0"
url="https://github.com/pulseaudio/pulseaudio.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="LGPL-2.1 or GPL-2.0"
agreementTargetFile="${srcDir}/libpulse/LICENSE ${srcDir}/libpulse/LGPL ${srcDIr}/libpulse/GPL"
args="
  -Ddaemon=false
  -Dman=false
  -Dtests=false
  -Ddatabase=simple
  -Dx11=disabled
  -Dgtk=disabled
  -Ddbus=disabled
  -Dalsa=enabled
  -Ddoxygen=false
  -Dopenssl=disabled
  -Dgsettings=disabled
"
deps="alsa-lib glib libsndfile libandroid-execinfo libandroid-glob"

pre_setup() {
  local crossFile="/tmp/cross-${targetArch}-meson.txt"
  sed -i \
    -e "s|^\(c_link_args = \[.*\)\]$|\1, '-Wl,--undefined-version']|" \
    -e "s|^\(cpp_link_args = \[.*\)\]$|\1, '-Wl,--undefined-version']|" \
    -e "/^\[properties\]/a has_function_iconv_open = false" \
    "${crossFile}"
}
