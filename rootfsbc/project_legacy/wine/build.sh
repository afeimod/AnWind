revision="wine-10.15"
url="https://github.com/wine-mirror/wine.git"
urlType="git"
arch="x86_64 aarch64"
buildSys="autotools"
license="LGPL-2.1"
args="
  --disable-tests
  --disable-wineandroid-drv
  --enable-tools
  --with-alsa
  --without-capi
  --without-coreaudio
  --without-cups
  --without-dbus
  --with-ffmpeg
  --with-fontconfig
  --with-freetype
  --without-gettextpo
  --without-gphoto
  --with-gnutls
  --without-gssapi
  --with-gstreamer
  --without-inotify
  --without-krb5
  --without-mingw
  --without-netapi
  --without-opencl
  --with-opengl
  --without-osmesa
  --without-oss
  --without-pcap
  --without-pcsclite
  --with-pthread
  --with-pulse
  --without-sane
  --without-sdl
  --without-udev
  --without-unwind
  --without-usb
  --without-v4l2
  --with-vulkan
  --with-wayland
  --without-xcomposite
  --without-xcursor
  --without-xfixes
  --without-xinerama
  --without-xinput
  --without-xinput2
  --without-xrandr
  --without-xrender
  --without-xshape
  --without-xshm
  --without-xxf86vm
  --without-x
"
deps="alsa-lib fontconfig freetype gnutls gstreamer ffmpeg pulseaudio xkeyboard-config libxkbcommon mesa vulkan-headers vulkan-icd-loader wayland wayland-protocols wine-tools"

pre_setup() {
  # autoreconf -fi
  # 交叉编译时必须指定 wine-tools 路径（宿主机构建的工具在 host_tools/wine-tools）
  # 校验宿主工具版本与本项目 revision 一致（winebuild --version 输出 "winebuild version X.Y"）
  local wineBuild="${wsDir}/host_tools/wine-tools/tools/winebuild/winebuild"
  local wantVer="${revision#wine-}"
  local haveVer=""
  [[ -x "$wineBuild" ]] && haveVer="$("$wineBuild" --version 2>/dev/null | awk '{print $3}')"
  if [[ "$haveVer" != "$wantVer" ]]; then
    echo "错误: host_tools/wine-tools 版本(${haveVer:-缺失}) 与 wine-amd64 ${wantVer} 不一致"
    echo "请先用 --rebuild wine-tools 重建宿主工具，再重新构建"
    exit 1
  fi
  # 先清理旧副本再复制，避免 --has-src 重跑时嵌套成 wine-tools/wine-tools
  rm -rf "${srcDir}/wine-amd64/wine-tools"
  cp -r -p "${wsDir}/host_tools/wine-tools" ${srcDir}/wine/
  args+=" --with-wine-tools=wine-tools"
  # --enable-archs 根据目标架构动态设置 PE/Cross 子架构：
  #   x86_64  -> i386,x86_64
  #   aarch64 -> arm64ec,aarch64,i386
  case "${targetArch}" in
    x86_64)  args+=" --enable-archs=i386,x86_64" ;;
    aarch64) args+=" --enable-archs=arm64ec,aarch64,i386" ;;
  esac
  # wayland-scanner 是宿主机工具，来自 wayland 项目构建的 host_tools/wayland
  PATH="${wsDir}/host_tools/wayland/bin:$PATH"
  # PE 侧编译器由 configure 自动检出为裸 clang（AC_CHECK_PROGS 对已有环境变量直接采用），
  # 补上 ccache 前缀，否则所有 dll 的 PE 编译都不走缓存（unix 侧走 CC 已带 ccache）
  export i386_CC="ccache clang"
  export x86_64_CC="ccache clang"
  export aarch64_CC="ccache clang"
  export arm64ec_CC="ccache clang"
}
