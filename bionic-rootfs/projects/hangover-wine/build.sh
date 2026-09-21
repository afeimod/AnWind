# 源码版本/URL 可用环境变量覆盖（CI 工作流用下拉选择传入）：
#   ANWIND_HANGOVER_TAG / ANWIND_WINE_URL / ANWIND_WINE_LABEL
# 源码支持三种来源（本配方即 arm64ec 形态配方，任意 wine 树皆可构建）：
#   hangover  AndreRH/wine        tag    hangover-<ver>   （默认）
#   official  wine-mirror/wine    tag    wine-<ver>       （WineHQ 官方源码 GitHub 镜像）
#   proton    ValveSoftware/wine  branch proton_<ver>     （Valve Proton 的 wine 源码树）
# 本目录的 bionic 补丁针对 hangover 树生成；official/proton 树结构相同
# （均为上游 wine 树），个别 hunk 可能因版本漂移失败——pre_setup 对此
# 容错（打印告警继续构建），失败 hunk 落 *.rej 供人工核查。
revision="${ANWIND_HANGOVER_TAG:-11.16}"
url="${ANWIND_WINE_URL:-https://github.com/AndreRH/wine/archive/refs/tags/hangover-${revision}.tar.gz}"
urlType="tar"
arch="aarch64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

# ============================================================
# AnWind bionic Wine（arm64ec 形态 / X11 / 新 WoW64）
# ------------------------------------------------------------
# 与 wine（x86_64，box64 加载）互补的第二个 Wine 运行时：
#   * 目标架构 aarch64（bionic），PE 侧 arm64ec+aarch64+i386（新 WoW64）
#     —— aarch64 设备原生运行，无需 box64 整机模拟
#   * 显示：仅 X11（--with-x 全套 X 扩展；无 wayland）
#   * 安装路径 $prefix/opt（启动器 WINE_HANGOVER 分支识别）
# llvm-mingw 必须用 20251202+：arm64ec PE 目标需要较新的 llvm/mingw-w64
# （与用户 glibc 流水线 wine-Arm.yml 的验证组合一致）
# ============================================================

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

deps="pthread-stub alsa-lib fontconfig freetype gnutls gstreamer ffmpeg pulseaudio xkeyboard-config libxkbcommon mesa vulkan-headers vulkan-icd-loader xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm libandroid-shmem"

# 静默编译：wine 的 make 会回显每条完整编译/链接命令（ccache 全路径），
# 单包就曾刷出 11.7 万行日志；-s 只关命令回显，编译器/链接器报错照常输出，
# 失败时仍能从 "make: *** [Makefile:...] Error 1" 定位到目标。
makeSilent=1

llvmMingwVersion="22"
llvmMingwDate="20251202"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-22.04-x86_64.tar.xz"
llvmMingwSha256=""

hangoverVersion="${revision}"
hangoverDebUrl="https://github.com/AndreRH/hangover/releases/download/hangover-${hangoverVersion}/hangover_${hangoverVersion}_ubuntu2204_jammy_arm64.tar"

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-hangover-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== hangover-wine 构建开始 ==="

  # 0. 源码树兼容性探测（official / proton / 自定义树均非 hangover 形态）
  #    a) git 树可能不含预生成的 configure（proton 分支如此）→ autogen.sh 生成
  #    b) arm64ec 能力探测：旧树（≤ wine 9.x 前后）无
  #       'aarch64|arm64ec' 校验分支 → 降级 --enable-archs=aarch64,i386
  #       （仍为新 WoW64：ARM64 PE 原生 + i386 PE；x86_64 PE 不支持，
  #        跑 x86_64 Windows 程序需 hangover 源或 x86_64 形态）
  #       ※ 实证：official-11.0 / proton_10.0 均支持 arm64ec（configure 0 退出），
  #         三大默认源不会触发降级
  if [[ ! -f "${srcDir}/${pjName}/configure" ]]; then
    if [[ -f "${srcDir}/${pjName}/autogen.sh" ]]; then
      echo "源码树无预生成 configure，运行 autogen.sh ..."
      ( cd "${srcDir}/${pjName}" && ./autogen.sh ) || { echo "autogen 失败"; return 1; }
    else
      echo "错误: 源码树既无 configure 也无 autogen.sh"
      return 1
    fi
  fi
  if grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure.ac" 2>/dev/null || \
     grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure" 2>/dev/null; then
    echo "源码树支持 arm64ec（--enable-archs 校验分支存在），保持 --enable-archs=arm64ec,aarch64,i386"
  else
    echo "⚠️ 源码树不支持 arm64ec（过旧/魔改树），降级 --enable-archs=aarch64,i386"
    echo "   （ARM64 PE 原生 + i386 PE 新 WoW64；x86_64 PE 请改用 hangover 源或 x86_64 形态）"
    args="${args/--enable-archs=arm64ec,aarch64,i386/--enable-archs=aarch64,i386}"
  fi

  # 1. 下载并设置 llvm-mingw 工具链
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
  # --forward 跳过已应用的 hunk；失败仅告警不中断（official/proton 树
  # 可能已含等价修正），失败 hunk 落 *.rej。
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      if ! sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  host 补丁 $(basename "${_patch}") 有 hunk 未应用（树差异，继续构建）"
      fi
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
  make ${makeSilent:+-s} -j$(nproc) __tooldeps__ || {
    echo "host build 失败"
    return 1
  }
  make ${makeSilent:+-s} -j$(nproc) -C nls || {
    echo "host build 失败"
    return 1
  }

  cd ..

  # 3. 主构建: 配置和编译 wine
  echo "=== 主构建: 配置 wine ==="
  load_env "$targetArch"
  cd "${srcDir}/${pjName}"

  # bionic 兼容补丁（路径/互斥锁属性/Socket IPX，与 wine 配方同源）
  # 补丁针对 hangover 树生成；official/proton 树个别 hunk 可能漂移，
  # 失败仅告警不中断，*.rej 留源码树供核查。
  local _patchFail=""
  for _patch in "${wsDir}/projects/${pjName}"/*.patch; do
    if [[ -f "${_patch}" ]] && [[ ! "${_patch}" == *".beforehostbuild" ]]; then
      echo "应用主补丁: $(basename "${_patch}")"
      if ! sed -e "s%@TERMUX_PREFIX@%${prefix}%g" \
        -e "s%@TERMUX_BASE_DIR@%/data/data/com.anwind/files/rootfs%g" \
        "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  主补丁 $(basename "${_patch}") 有 hunk 未应用（源码树差异，继续构建）"
        _patchFail+=" $(basename "${_patch}")"
      fi
    fi
  done
  if [[ -n "${_patchFail}" ]]; then
    echo "⚠️⚠️ 以下补丁存在失败 hunk:${_patchFail}"
    echo "    构建继续；若运行期异常请检查源码树内 *.rej 文件"
  fi

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

  # linux部分强制 ndk clang (CC部分已经指定版本与ccache)
  export i386_CC="$CC"
  export x86_64_CC="$CC"
  export aarch64_CC="$CC"
  export arm64ec_CC="$CC"

  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${_llvmMingwDir}/bin/clang"
}

pre_package() {
  # hangover-wine 安装在 $prefix/opt（非 opt/wine），此处仅补标识文件供
  # anwind-container / 用户识别该运行时形态；kind 依源码树实际能力落戳
  # （arm64ec = 完整新 WoW64；arm64 = 极旧树降级形态，三大默认源不触发）
  local _wineDir="${destDir}${prefix}/opt"
  if [[ -d "${_wineDir}" ]]; then
    local _kind="arm64-wow64-x11"
    if grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure.ac" 2>/dev/null || \
       grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure" 2>/dev/null; then
      _kind="arm64ec-wow64-x11"
    fi
    cat > "${_wineDir}/.anwind-wine-info" << EOF
kind=${_kind}
tag=${ANWIND_WINE_LABEL:-hangover-${revision}}
backend=native
EOF
  fi
}
