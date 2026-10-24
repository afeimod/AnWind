revision="wine-11.0"
url="https://github.com/wine-mirror/wine.git"
urlType="git"
arch="aarch64"
buildSys="autotools"
license="LGPL-2.1"

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt
  --exec-prefix=$prefix/opt/
  --includedir=$prefix/opt/include
  --libdir=$prefix/opt/lib
  --enable-archs=i386,x86_64
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

deps="alsa-lib fontconfig freetype gnutls gstreamer ffmpeg fluidsynth pulseaudio xkeyboard-config libxkbcommon mesa vulkan-headers vulkan-icd-loader wayland wayland-protocols"

llvmMingwVersion="21"
llvmMingwDate="20250319"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-20.04-x86_64.tar.xz"
llvmMingwSha256="ab2a1489416fa82b3e85e88cb877053ee8a591993408caf076737d8de5ae72ca"

pre_setup() {
  # 宿主 wine-tools 与 llvm-mingw 均持久化到 host_tools，目录名带 revision/version，
  # 存在即跳过构建/下载，避免每次重建重复编译浪费时间
  # 工具树来自 wine-mirror 源码，与 hangover-wine 的 wine-tools-hangover-* 互不共用
  local _hostTools="${wsDir}/host_tools/wine-tools-amd64-${revision#wine-}"
  local _llvmMingwDir="${wsDir}/host_tools/llvm-mingw-${llvmMingwVersion}"

  echo "=== wine-amd64 构建开始 ==="

  # 1. 下载并设置 llvm-mingw 工具链
  if [[ ! -d "${_llvmMingwDir}" ]]; then
    echo "下载 llvm-mingw 工具链..."
    mkdir -p "${wsDir}/host_tools"
    wget -P "${wsDir}/host_tools" "${llvmMingwUrl}" || {
      echo "下载 llvm-mingw 失败"
      return 1
    }
    local _tarFile="${wsDir}/host_tools/$(basename "${llvmMingwUrl}")"
    echo "${llvmMingwSha256}  ${_tarFile}" | sha256sum -c --status - || {
      echo "llvm-mingw sha256 校验失败 => ${_tarFile}"
      rm -f "${_tarFile}"
      return 1
    }
    rm -rf "${_llvmMingwDir}-tmp"
    mkdir -p "${_llvmMingwDir}-tmp"
    tar -C "${_llvmMingwDir}-tmp" --strip-component=1 -xf "${_tarFile}"
    mv "${_llvmMingwDir}-tmp" "${_llvmMingwDir}"
    rm -rf "${_llvmMingwDir}-tmp"
  fi
  export PATH="${_llvmMingwDir}/bin:${wsDir}/host_tools/wayland/bin:${PATH}"

  load_env_host

  cd "${srcDir}/${pjName}"

  # 2. 宿主 wine-tools：持久化到 host_tools/wine-tools-amd64-<version>，
  #    已构建（winebuild 可执行）则跳过，主构建通过 --with-wine-tools 引用该目录
  if [[ -x "${_hostTools}/tools/winebuild/winebuild" ]]; then
    echo "wine-tools-amd64 ${revision#wine-} 已存在，跳过宿主工具构建 => ${_hostTools}"
  else
    for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
      if [[ -f "${_patch}" ]]; then
        echo "应用 host build 补丁: $(basename "${_patch}")"
        # host 补丁不含占位符，直接应用
        patch --silent --forward -p1 < "${_patch}" || {
          echo "host build 补丁应用失败: ${_patch}"
          return 1
        }
      fi
    done

    echo "构建宿主机 wine-tools => ${_hostTools}"
    rm -rf "${_hostTools}"
    mkdir -p "${_hostTools}"
    cd "${_hostTools}"
    FREETYPE_CFLAGS=-I/usr/include/freetype2 FREETYPE_LIBS=-lfreetype "${srcDir}/${pjName}/configure" \
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

    cd "${srcDir}/${pjName}"
  fi

  # 3. 主构建: 配置和编译 wine
  echo "=== 主构建: 配置 wine ==="
  load_env "$targetArch"
  cd "${srcDir}/${pjName}"

  CFLAGS="${CFLAGS/-Oz/}"
  CXXFLAGS="${CXXFLAGS/-Oz/}"
  CPPFLAGS="${CPPFLAGS/-Oz/}"
  CFLAGS="${CFLAGS/-fstack-protector-strong/}"
  CXXFLAGS="${CXXFLAGS/-fstack-protector-strong/}"
  CPPFLAGS="${CPPFLAGS/-fstack-protector-strong/}"
  LDFLAGS="${LDFLAGS/-Wl,-z,relro,-z,now/}"
  export LDFLAGS+=" -Wl,--rosegment -Wl,--pack-dyn-relocs=none"

  # linux部分强制 ndk clang (CC部分已经指定版本与ccache)
  export i386_CC="$CC"
  export x86_64_CC="$CC"
  export aarch64_CC="$CC"
  export arm64ec_CC="$CC"

  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=${_hostTools} --with-mingw=${_llvmMingwDir}/bin/clang"
}
