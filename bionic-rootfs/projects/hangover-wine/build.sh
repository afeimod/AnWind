revision="11.16"
url="https://github.com/AndreRH/wine/archive/refs/tags/hangover-${revision}.tar.gz"
urlType="tar"
arch="aarch64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt
  --exec-prefix=$prefix/opt/
  --includedir=$prefix/opt/include
  --libdir=$prefix/opt/lib
  --enable-archs=arm64ec,aarch64,i386
  --enable-nls
  --disable-tests
  --disable-wineandroid-drv
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
  --without-wayland
  --with-x
  --with-xcomposite
  --with-xcursor
  --with-xfixes
  --with-xinerama
  --with-xinput
  --with-xinput2
  --with-xrandr
  --with-xrender
  --with-xshape
  --with-xshm
  --with-xxf86vm
"

deps="alsa-lib fontconfig freetype gnutls gstreamer ffmpeg pulseaudio xkeyboard-config libxkbcommon mesa vulkan-headers vulkan-icd-loader xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm"

llvmMingwVersion="21"
llvmMingwDate="20250319"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-20.04-x86_64.tar.xz"
llvmMingwSha256="ab2a1489416fa82b3e85e88cb877053ee8a591993408caf076737d8de5ae72ca"

hangoverVersion="${revision}"
hangoverDebUrl="https://github.com/AndreRH/hangover/releases/download/hangover-${hangoverVersion}/hangover_${hangoverVersion}_ubuntu2204_jammy_arm64.tar"

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-hangover-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== hangover-wine 构建开始 ==="

  # 1. 下载并设置 llvm-mingw 工具链
  if [[ ! -d "${_llvmMingwDir}" ]]; then
    echo "下载 llvm-mingw 工具链..."
    mkdir -p "${wsDir}/tmp"
    wget -P "${wsDir}/tmp" "${llvmMingwUrl}" || {
      echo "下载 llvm-mingw 失败"
      return 1
    }
    local _tarFile="${wsDir}/tmp/$(basename "${llvmMingwUrl}")"
    mkdir -p "${_llvmMingwDir}-tmp"
    tar -C "${_llvmMingwDir}-tmp" --strip-component=1 -xf "${_tarFile}"
    mv "${_llvmMingwDir}-tmp" "${_llvmMingwDir}"
    rm -rf "${_llvmMingwDir}-tmp"
  fi
  export PATH="${_llvmMingwDir}/bin:${PATH}"

  load_env_host

  cd "${srcDir}/${pjName}"
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent -p1
    fi
  done

  mkdir -p "${srcDir}/${pjName}/wine-tools-build"
  cd "${srcDir}/${pjName}/wine-tools-build"
  FREETYPE_CFLAGS=-I/usr/include/freetype2 FREETYPE_LIBS=-lfreetype ../configure \
    --enable-win64 \
    --without-x \
    --with-freetype \
    --without-fontconfig \
    --without-gnutls \
    --without-cups \
    --without-pulse \
    --without-alsa \
    --without-gstreamer \
    --without-opengl \
    --without-vulkan \
    --without-wayland \
    --disable-tests || {
      echo "host configure 失败"
      return 1
    }
  make -j$(nproc) __tooldeps__ || {
    echo "host build 失败"
    return 1
  }
  make -j$(nproc) -C nls || {
    echo "host build 失败"
    return 1
  }

  cd ..

  # 3. 主构建: 配置和编译 wine
  echo "=== 主构建: 配置 wine ==="
  load_env "$targetArch"
  cd "${srcDir}/${pjName}"

  for _patch in "${wsDir}/projects/${pjName}"/*.patch; do
    if [[ -f "${_patch}" ]] && [[ ! "${_patch}" == *".beforehostbuild" ]]; then
      echo "应用主补丁: $(basename "${_patch}")"
      sed -e "s%@TERMUX_PREFIX@%${prefix}%g" \
        -e "s%@TERMUX_BASE_DIR@%/data/data/com.anwind/files/rootfs%g" \
        "${_patch}" | patch --silent -p1
    fi
  done

  CFLAGS="${CFLAGS/-Oz/}"
  CXXFLAGS="${CXXFLAGS/-Oz/}"
  CPPFLAGS="${CPPFLAGS/-Oz/}"
  CFLAGS="${CFLAGS/-fstack-protector-strong/}"
  CXXFLAGS="${CXXFLAGS/-fstack-protector-strong/}"
  CPPFLAGS="${CPPFLAGS/-fstack-protector-strong/}"
  LDFLAGS="${LDFLAGS/-Wl,-z,relro,-z,now/}"
  export LDFLAGS+=" -Wl,--rosegment"

  # linux部分强制 ndk clang (CC部分已经指定版本与ccache)
  export i386_CC="$CC"
  export x86_64_CC="$CC"
  export aarch64_CC="$CC"
  export arm64ec_CC="$CC"

  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${wsDir}/tmp/llvm-mingw-21/bin/clang"
}
