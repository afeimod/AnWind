revision="${ANWIND_WINE_TAG:-hangover-11.16}"
url="${ANWIND_WINE_URL:-https://github.com/AndreRH/wine/archive/refs/tags/hangover-11.16.tar.gz}"
urlType="tar"
arch="x86_64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

# ============================================================
# AnWind bionic Wine（x86_64 / X11 / WoW64）
# ------------------------------------------------------------
# 与 hangover-wine（aarch64 + qemu）互补的第二个 Wine 运行时：
#   * 目标架构 x86_64（bionic），PE 侧 i386+x86_64（新 WoW64）
#   * aarch64 设备上由 box64 加载运行（MiceWine 同款模型：
#     `box64 $PREFIX/opt/wine/bin/wine ...`）
#   * x86_64 设备上原生运行，无需模拟器
#   * 显示：仅 X11（--with-x 全套 X 扩展；无 wayland）
#     X 服务为 AnWind 内置 termux-x11/libXlorie（App 侧
#     tmp/.X11-unix/Xn，默认 :1），rootfs 内 libxcb/xtrans
#     已按 @APP_PREFIX@ 打好 socket 路径补丁
#   * 音频：PulseAudio（PULSE_SERVER=127.0.0.1:4713，由
#     anwind-x11 会话拉起；rootfs 亦自带 pulseaudio）
# 源码默认沿用 AndreRH/wine hangover-11.16（与 hangover-wine
# 配方同源，本目录的 bionic 补丁即针对该树生成，保证可套用）。
# 可用环境变量覆盖：
#   ANWIND_WINE_URL / ANWIND_WINE_TAG（CI 支持任意 wine 源）
# ============================================================

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt/wine
  --libdir=$prefix/opt/wine/lib
  --includedir=$prefix/opt/wine/include
  --enable-archs=i386,x86_64
  --enable-nls
  --disable-tests
  --disable-wineandroid-drv
  --without-alsa
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
  --without-inotify
  --without-krb5
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
  --x-libraries=$prefix/lib
  --x-includes=$prefix/include
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

# 构建依赖全部来自 rootfs 既有配方（双架构可用，缺什么 build 系统
# 依据 toposort 自动补齐）：
deps="fontconfig freetype gnutls gstreamer ffmpeg pulseaudio mesa vulkan-headers vulkan-icd-loader xkeyboard-config libxkbcommon xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm libandroid-shmem"

# 静默编译：wine 的 make 会回显每条完整编译/链接命令（ccache 全路径），
# 单包就曾刷出 11.7 万行日志；-s 只关命令回显，编译器/链接器报错照常输出，
# 失败时仍能从 "make: *** [Makefile:...] Error 1" 定位到目标。
makeSilent=1

# PE 交叉编译器：llvm-mingw（与 hangover-wine 同版本， proven）
llvmMingwVersion="21"
llvmMingwDate="20250319"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-20.04-x86_64.tar.xz"
llvmMingwSha256="ab2a1489416fa82b3e85e88cb877053ee8a591993408caf076737d8de5ae72ca"

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== AnWind bionic wine（x86_64/X11/WoW64）构建开始 ==="

  # 1. 下载并设置 llvm-mingw 工具链（i686/x86_64 PE 目标）
  if [[ ! -d "${_llvmMingwDir}" ]]; then
    echo "下载 llvm-mingw 工具链..."
    mkdir -p "${wsDir}/tmp"
    wget -nv -P "${wsDir}/tmp" "${llvmMingwUrl}" || {
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

  # 2. host 构建补丁（winegcc/winebuild 目标修正，bionic 树必需）
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent -p1
    fi
  done

  # 3. 宿主 wine 工具链（winebuild/wrc/widl/nls 皆为构建机本地产物）
  #    --enable-win64 --without-x：工具与 X11/bionic 无关，最小化依赖
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
  make ${makeSilent:+-s} -j$(nproc) __tooldeps__ || {
    echo "host build 失败"
    return 1
  }
  make ${makeSilent:+-s} -j$(nproc) -C nls || {
    echo "host nls build 失败"
    return 1
  }

  cd ..

  # 4. 主构建：bionic 交叉编译（Unix 侧 x86_64-android，PE 侧 i386/x86_64）
  echo "=== 主构建: 配置 wine（--with-x X11，无 wayland） ==="
  load_env "$targetArch"
  cd "${srcDir}/${pjName}"

  # bionic 兼容补丁（路径/互斥锁属性/Socket IPX，与 hangover-wine 同源）
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
  # -landroid-shmem：winex11.drv 的 MIT-SHM 路径引用 libandroid_shmget/shmat/
  # shmctl/shmdt（prefix/include/sys/shm.h 由 libandroid-shmem 提供，宏把标准
  # shm 接口映射到 libandroid_ 前缀符号）。LDFLAGS 会进每个 unix .so 的链接行
  # （lld 默认 as-needed，未用到该库的模块不会引入依赖），缺失时 winex11.so
  # 链接报 "undefined symbol: libandroid_shmget" 直接失败。
  export LDFLAGS+=" -Wl,--rosegment -landroid-shmem"

  # PE→Unix thunk 亦用 ndk clang（CC 已含版本与 ccache）
  export i386_CC="$CC"
  export x86_64_CC="$CC"

  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${_llvmMingwDir}/bin/clang"
}

pre_package() {
  # 注意：不可定义 install() —— build 系统一旦发现 install() 就会
  # 跳过 `make install`（hangover-wine 亦如此，靠 configure 的
  # --prefix=$prefix/opt/wine + DESTDIR 落盘）。此处仅补版本戳，
  # 供 anwind-container 识别 wine 运行时。
  local _wineDir="${destDir}${prefix}/opt/wine"
  if [[ -d "${_wineDir}" ]]; then
    cat > "${_wineDir}/.anwind-wine-info" << EOF
kind=x86_64-wow64-x11
tag=${revision}
backend=box64|native
EOF
  else
    echo "警告: 未找到 wine 安装目录 ${_wineDir}，可能 make install 失败"
  fi
}

