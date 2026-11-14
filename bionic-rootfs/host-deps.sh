wsDir="$(dirname $(readlink -f "$0"))"

# ============================================================
# 宿主依赖全集（参考 yml 对齐轮）
# ------------------------------------------------------------
# 目标：与三个参考工作流的宿主依赖【并集】完全一致：
#   * wine-wow64-no.yml  BASE_DEPS + 多媒体/音频依赖（两个 job 并集）
#   * build-Allmesa.yml  mesa 基础依赖（aarch64 列表，armhf 交叉
#                        工具链部分按其基础列表一并装上）
#   * build-box64.yml    box64 依赖（cmake build-essential gcc git
#                        patchelf xz-utils）
# 分层策略（保证 CI 的 ubuntu-24.04 上与参考逐项一致，其它发行版
# 优雅降级不炸构建）：
#   1) archPkgs / debianPkgs：基础构建工具 + 参考 yml 中所有
#      「发行版稳定包名」的包 —— 严格安装，缺一即失败；
#   2) archRefPkgs / debianRefPkgs：参考 yml 中「版本号后缀 / 发行版
#      专属」的包（llvm-19、libclc-20-dev、gstreamer1.0-qt5、
#      gstreamer1.0-gtk3、language-pack-zh-hans 等）—— 探测式安装
#      （源里存在才装，不存在仅告警跳过）。CI 的 ubuntu-24.04
#      （noble-updates + noble-universe）上这些包全部存在，与参考
#      实际安装结果一致；bookworm 等旧发行版无 llvm-19/libclc-20-dev，
#      跳过不影响交叉构建。
# 注意：这些参考对齐包绝大多数是参考 yml【原生构建】（ARM runner 上
#   直接编 glibc 产物）所消费的开发库；本构建系统是 NDK 交叉构建，
#   目标侧依赖（X11/GStreamer/SDL 等）由 bionic-rootfs/projects/ 的
#   配方按拓扑序自产，宿主侧包不参与产物。按参考全量装上的目的：
#   a) 与参考环境逐项对齐，规避"宿主缺依赖"类排查噪音；
#   b) wine autogen（proton 树）/ 工具链 / 冒烟测试等宿主侧环节
#      有完整的可选依赖可用。
# ============================================================

archPkgs=(
  git
  wget
  7zip
  base-devel
  ccache
  cmake
  meson
  ninja
  bzip2
  xz
  zstd
  lzip
  lzop
  ncompress
  lz4
  unzip

  gobject-introspection

  gperf

  glslang

  python-pip

  bison
  flex

  pkgconf
  gettext
  freetype2

  glib2
  glib2-devel

  # ---- 参考 yml 对齐：宿主工具类（wine BASE_DEPS / build-box64.yml）----
  # perl：proton 树 autogen.sh 依赖；debootstrap/bubblewrap：参考
  # BASE_DEPS 的容器工具；patchelf：参考 build-box64.yml；file：其
  # glibc 形态工作流配套；python-setuptools/python-yaml/python-mako：
  # 参考 python3-setuptools/python3-yaml/python3-mako 的 Arch 对应名
  # （mesa 的 meson 构建在配置与编译期都 import mako）
  perl
  debootstrap
  bubblewrap
  patchelf
  file
  python-setuptools
  python-yaml
  python-mako
)

# 参考对齐依赖（Arch 包名，探测式安装：pacman -Si 查到才装）。
# Debian→Arch 包名映射（wine BASE_DEPS / 多媒体 / mesa 依赖）：
#   libx11-dev→libx11          libsdl2-dev→sdl2           libglu-dev→glu
#   libgl-dev/libosmesa6-dev/libegl1-mesa-dev/mesa-vulkan-drivers→mesa/libglvnd
#   libudev-dev→systemd-libs   libtiff-dev→libtiff        libpcap-dev→libpcap
#   vulkan-tools/libvulkan1→vulkan-tools/vulkan-icd-loader
#   gstreamer1.0-plugins-*→gst-plugins-*  gstreamer1.0-pulseaudio→gst-plugin-pulse
#   libasound2-plugins→alsa-plugins  gstreamer1.0-libav→gst-libav
#   llvm-19→llvm（滚动版本）    libclc-20-dev→libclc
#   g++-arm-linux-gnueabihf→arm-linux-gnueabihf-gcc（在官方源才装）
#   gcc-multilib/g++-multilib→gcc-multilib（Arch 由 gcc-multilib 提供 g++ 32 位）
archRefPkgs=(
  gcc-multilib
  sdl2
  glu
  libglvnd
  mesa
  fontconfig
  libpcap
  dbus
  openssl
  alsa-lib
  alsa-plugins
  pulseaudio
  libpulse
  systemd-libs
  libcups
  libjpeg-turbo
  libpng
  libtiff
  libxml2
  expat
  libdrm
  vulkan-headers
  vulkan-tools
  vulkan-icd-loader
  mingw-w64
  llvm
  libclc
  libx11
  libxext
  libxi
  libxrandr
  libxcursor
  libxcomposite
  libxdamage
  libxfixes
  libxxf86vm
  libxrender
  libxinerama
  libxshmfence
  libxcb
  libunwind
  gstreamer
  gst-plugins-base
  gst-plugins-good
  gst-plugins-bad
  gst-plugins-ugly
  gst-libav
  gst-plugin-pulse
  mpg123
  openal
  faad2
  flac
  libvorbis
  x264
  x265
  libvpx
  libtheora
  arm-linux-gnueabihf-gcc
)

debianPkgs=(
  build-essential
  git
  wget
  p7zip-full
  ccache
  cmake
  ninja-build
  bzip2
  xz-utils
  zstd
  lzip
  lzop
  ncompress
  lz4
  unzip
  gobject-introspection
  gperf
  glslang-tools
  python3-pip
  python3-yaml
  bison
  flex
  libtool
  autoconf
  automake
  pkgconf
  gettext
  # Ubuntu 24.04(noble) 起 autopoint 被拆为独立二进制包（同一 gettext 源码包
  # 产出，但 gettext 包内不再含 /usr/bin/autopoint）——只装 gettext 的话
  # autopoint 依然缺失。fontconfig 等包的 autogen.sh 无条件检查 autopoint
  # （且其 configure.ac 带 AM_GNU_GETTEXT，autoreconf -i 也会真调用它），
  # 缺它会在 fontconfig 处报 "You must have autopoint installed" 直接失败。
  autopoint
  libfreetype-dev
  locales
  # 注意：glib 开发工具（glib-mkenums/gdbus-codegen/gio-querymodules）所在
  # 包名不写死在这里——它在不同发行版上包名不同，见下方安装前的动态探测。
  # language-pack-zh-hans 已移至下方 debianRefPkgs 探测式安装（Ubuntu 专属
  # 包名，Debian 无此包——旧版直接放在本数组里会让 Debian 上 apt install
  # 整体失败）。
  python3-setuptools
  python3-dev
  python3-mako

  # ---- 参考 yml 对齐：build-box64.yml ----
  # cmake/build-essential/gcc/git/xz-utils 已在列；patchelf 为参考 yml
  # 显式安装项，file 为其 glibc 形态工作流（build-box64.yml）配套的
  # ELF 校验工具。
  patchelf
  file

  # ---- 参考 yml 对齐：wine-wow64-no.yml BASE_DEPS ----
  # perl 供 proton 树 autogen.sh；debootstrap/bubblewrap 为参考 BASE_DEPS
  # 的容器构建工具（bionic 交叉构建不消费，按参考装齐）；mingw-w64 在
  # 本构建系统仅作参考对齐 —— PE 侧交叉编译实际使用 llvm-mingw（见
  # projects/wine 与 projects/hangover-wine 配方），宿主 mingw-w64
  # 不参与产物；gcc-multilib/g++-multilib 为参考的 32 位支持工具链。
  debootstrap
  perl
  bubblewrap
  gcc-multilib
  g++-multilib
  libx11-dev
  libxext-dev
  libxi-dev
  libxrandr-dev
  libxcursor-dev
  libxcomposite-dev
  libxdamage-dev
  libxfixes-dev
  libxxf86vm-dev
  libxrender-dev
  libxinerama-dev
  libgl-dev
  libsdl2-dev
  # 参考 yml 写的 libglu-dev 是 libglu1-mesa-dev 的虚拟包名，装实包等价
  # （虚拟包名在部分发行版会有多提供方歧义）。
  libglu1-mesa-dev
  libosmesa6-dev
  # 参考 yml 的 libfreetype6-dev 与上方 libfreetype-dev 为同一源码包
  # 产出（noble 起 libfreetype-dev 为实包名），已在上方原列表中。
  libfontconfig1-dev
  libpcap-dev
  libdbus-1-dev
  libssl-dev
  libasound2-dev
  libpulse-dev
  libudev-dev
  libcups2-dev
  libjpeg-dev
  libpng-dev
  libtiff-dev
  libxml2-dev
  libvulkan-dev
  vulkan-tools
  libvulkan1
  mesa-vulkan-drivers
  mingw-w64
  libgettextpo-dev

  # ---- 参考 yml 对齐：wine-wow64-no.yml 多媒体/音频依赖（两 job 并集）----
  # 参考 proton job 的 gstreamer1.0-* 通配在此展开为具体包名（与 wine
  # job 的显式清单取并集），确定性安装、不依赖 apt 的通配展开。
  libunwind-dev
  libgstreamer1.0-dev
  libgstreamer-plugins-base1.0-dev
  libgstreamer-plugins-good1.0-dev
  libgstreamer-plugins-bad1.0-dev
  gstreamer1.0-plugins-base
  gstreamer1.0-plugins-good
  gstreamer1.0-plugins-bad
  gstreamer1.0-plugins-ugly
  gstreamer1.0-libav
  gstreamer1.0-pulseaudio
  gstreamer1.0-alsa
  gstreamer1.0-gl
  libmpg123-dev
  libopenal-dev
  libfaad-dev
  libflac-dev
  libvorbis-dev
  libx264-dev
  libx265-dev
  libvpx-dev
  libtheora-dev

  # ---- 参考 yml 对齐：build-Allmesa.yml 基础依赖（aarch64 列表）----
  # XCB 全家 + DRM/GBM/EGL 开发包；python3-wheel / libatomic1 /
  # pulseaudio（宿主守护，供冒烟）/ libasound2-plugins（ALSA→pulse
  # 桥）；g++-arm-linux-gnueabihf 在参考中用于 armhf 交叉（本构建
  # 系统不构建 armhf，按参考基础列表照样装上）。llvm-19 / libclc-20-dev
  # 带版本号后缀（noble-updates / noble-universe 有，bookworm 无）→
  # 移至下方 debianRefPkgs 探测式安装。
  libx11-xcb-dev
  libxcb1-dev
  libxcb-shm0-dev
  libxcb-glx0-dev
  libxcb-dri3-dev
  libxcb-present-dev
  libxcb-randr0-dev
  libxcb-sync-dev
  libxcb-xfixes0-dev
  libxshmfence-dev
  libdrm-dev
  libgbm-dev
  libegl1-mesa-dev
  libexpat1-dev
  libatomic1
  python3-wheel
  pulseaudio
  libasound2-plugins
  g++-arm-linux-gnueabihf
)

# 参考对齐依赖（Debian/Ubuntu 名，探测式安装：apt-cache show 查到才装，
# 装不上仅告警不阻断）。CI 的 ubuntu-24.04（noble，含 updates/universe）
# 上全部存在，实际安装结果与参考 yml 逐项一致：
#   llvm-19 / libclc-20-dev     build-Allmesa.yml 的 LLVM 19 工具链与
#                               libclc（noble-updates / noble-universe）。
#                               ※ 交叉构建不消费：bionic mesa 为
#                               -Dllvm=disabled（llvmpipe 需要目标架构
#                               的 LLVM，宿主 llvm-19 无法链入 Android
#                               .so，详见 projects/mesa/build.sh 注释），
#                               装上仅为与参考环境对齐。
#   gstreamer1.0-qt5            wine-wow64-no.yml 多媒体列表（noble 有，
#   gstreamer1.0-gtk3           部分 Debian 版本无 → 探测）
#   language-pack-zh-hans       wine BASE_DEPS 的构建机中文翻译数据
#                               （Ubuntu 专属包名，Debian 无）
debianRefPkgs=(
  llvm-19
  libclc-20-dev
  gstreamer1.0-qt5
  gstreamer1.0-gtk3
  language-pack-zh-hans
)

if [[ -f /etc/os-release ]]; then
  . /etc/os-release
  # ID 直接匹配；衍生发行版（manjaro/cachyos/endeavouros 等）按 ID_LIKE 兜底
  distro="${ID}"
  [[ "${ID_LIKE:-}" == *arch* ]] && distro="arch"
  [[ "${ID_LIKE:-}" == *debian* ]] && distro="debian"

  case "${distro}" in
  arch)
    echo "当前为archlinux"
    echo "需要安装 ${archPkgs[@]}"
    pacman-key --init
    pacman -Syyu --noconfirm || exit 1
    pacman -S --noconfirm --needed ${archPkgs[@]} || exit 1
    pip install PyYAML --break-system-packages || python3 -m pip install PyYAML --break-system-packages || true
    # 参考对齐依赖（Arch 名）：探测式安装 —— pacman -Si 查到的才装，
    # 查不到的（如部分发行版无 arm-linux-gnueabihf-gcc / libclc）跳过
    # 并告警。Arch 滚动发布包名与 Debian 系差异较大，缺失仅影响参考
    # 对齐，不影响本构建系统交叉编译。
    _archRefAvail=()
    for _p in "${archRefPkgs[@]}"; do
      if pacman -Si "${_p}" >/dev/null 2>&1; then
        _archRefAvail+=("${_p}")
      else
        echo "提示: Arch 源无 ${_p}（参考 yml 对齐项，跳过）"
      fi
    done
    if [[ ${#_archRefAvail[@]} -gt 0 ]]; then
      echo "参考对齐依赖（探测命中 ${#_archRefAvail[@]} 项）: ${_archRefAvail[*]}"
      pacman -S --noconfirm --needed "${_archRefAvail[@]}" \
        || echo "警告: 参考对齐依赖安装失败（继续，不影响交叉构建）"
    fi
    # zh_CN locale 一并生成（对齐参考 yml 的中文语言环境步骤）
    sed -i 's/^#[[:space:]]*\(en_US.UTF-8\)/\1/;s/^#[[:space:]]*\(zh_CN.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  debian|ubuntu)
    echo "当前为 $ID"
    echo "交互式将取消,自动完成安装"
    export DEBIAN_FRONTEND=noninteractive
    apt update || exit 1
    # 不做全量 apt upgrade：CI runner 上会下载 300MB+ 更新并触发
    # firefox→snap 转换（连带下载 snapd/core24/mesa-2404/bare 等 snap），
    # 既拖慢构建又可能在受限环境失败；runner 镜像已预置可用工具链，
    # 本脚本只需补装下面的构建依赖即可。

    # glib 开发工具（glib-mkenums / gdbus-codegen / gio-querymodules）
    # 所在包名随发行版而异，写死任何一个都会在别的发行版上
    # "Unable to locate package" 直接失败：
    #   - Ubuntu 22.04/24.04(noble): libglib2.0-dev-bin（工具都在里面）
    #   - Debian trixie 及更新(glib>=2.84): 拆分为 libgio-2.0-dev-bin
    #     （此时 libglib2.0-dev-bin 只剩 gi-* 工具）
    # 因此改为安装前动态探测：存在哪个装哪个，两个都在就都装。
    GLIB_DEV_BIN_PKGS=()
    for cand in libglib2.0-dev-bin libgio-2.0-dev-bin; do
      if apt-cache show "${cand}" >/dev/null 2>&1; then
        GLIB_DEV_BIN_PKGS+=("${cand}")
        echo "检测到 glib 开发工具包: ${cand}"
      fi
    done
    if [[ ${#GLIB_DEV_BIN_PKGS[@]} -eq 0 ]]; then
      echo "错误: 当前 apt 源中既无 libglib2.0-dev-bin 也无 libgio-2.0-dev-bin!"
      exit 1
    fi

    echo "需要安装 ${debianPkgs[@]} ${GLIB_DEV_BIN_PKGS[@]}"
    apt install -y ${debianPkgs[@]} ${GLIB_DEV_BIN_PKGS[@]} || exit 1

    # 参考对齐依赖：探测式安装（apt-cache show 查到的才装）。
    # CI 的 ubuntu-24.04（noble + updates + universe）上以下包全部
    # 存在，与参考 yml 的宿主依赖逐项一致；bookworm 等旧发行版无
    # llvm-19 / libclc-20-dev 时跳过，不影响交叉构建。
    for _p in "${debianRefPkgs[@]}"; do
      if apt-cache show "${_p}" >/dev/null 2>&1; then
        apt install -y "${_p}" \
          || echo "警告: ${_p} 安装失败（参考 yml 对齐项，继续）"
      else
        echo "提示: 当前源无 ${_p}（参考 yml 对齐项，跳过）"
      fi
    done
    # meson 通过 pip 安装较新版本（GitHub Actions 的 ubuntu-24.04 自带
    # meson 1.3.x，部分包如新版 mesa/xorgproto 需要更高版本）
    # 注意：
    # 1. ubuntu-24.04 无 `pip` 命令（只有 python3 -m pip），旧脚本用
    #    `pip` 会静默失败 → meson 未安装 → 工作流里 `meson --version` 报 127
    # 2. 绝不能 `--upgrade pip`：apt 安装的 pip 无 RECORD 文件，升级时
    #    卸载会报 "Cannot uninstall pip ... RECORD file not found" 而整条
    #    命令失败，连带 mako 也装不上（mesa 构建必需 mako）
    # 3. 必须用 --ignore-installed：直接装新副本、不卸载 apt 管理的
    #    PyYAML/meson/mako，绕开 "Cannot uninstall PyYAML 6.0.1,
    #    RECORD file not found. Hint: The package was installed by debian."
    python3 -m pip install --break-system-packages --ignore-installed mako PyYAML \
      || { echo "mako/PyYAML 安装失败!" && exit 1; }
    python3 -m pip install --break-system-packages --ignore-installed meson ninja \
      || python3 -m pip install --ignore-installed meson ninja \
      || { echo "meson/ninja 安装失败!" && exit 1; }
    hash -r
    command -v meson >/dev/null 2>&1 || export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
    hash -r
    command -v meson >/dev/null 2>&1 || { echo "meson 仍不可用!" && exit 1; }
    echo "meson 版本: $(meson --version)"
    echo "ninja 版本: $(ninja --version)"
    # mesa 的 meson 构建在配置与编译期都会 import mako，必须显式验证
    python3 -c "import mako" 2>/dev/null \
      || { echo "python3 mako 模块不可用!（mesa 构建必需）" && exit 1; }
    echo "mako 版本: $(python3 -c 'import mako; print(mako.__version__)')"
    command -v libtoolize >/dev/null 2>&1 \
      || { echo "libtoolize 不可用!（libexpat 等包 autoreconf 必需）" && exit 1; }
    libtoolize --version | head -1
    # libtool 二进制兜底：部分上游 autogen.sh（如 flac，虽然当前已改走 CMake）
    # 会 `command -v libtool`。此前出现过 libtoolize 可用但 libtool 缺失、
    # 构建 40 分钟到后面的包才失败的坑，这里显式检查并尝试修复。
    if ! command -v libtool >/dev/null 2>&1; then
      echo "警告: libtool 二进制缺失，尝试显式重装..."
      apt-get install -y --reinstall libtool || true
      command -v libtool >/dev/null 2>&1 \
        || echo "警告: libtool 仍不可用（当前配方链已不依赖；若新增 autogen 包需注意）"
    fi
    command -v libtool >/dev/null 2>&1 && libtool --version | head -1 || true
    # 参考 yml 对齐的中文语言环境（wine --enable-nls 构建（msgfmt 编译
    # po/zh_CN.po → share/locale/zh_CN/LC_MESSAGES/wine.mo）在中文
    # locale 环境下产出完整翻译，rootfs-locale 的 localedef 也依赖
    # locales 包（已在 debianPkgs）。language-pack-zh-hans 为 Ubuntu
    # 专属包名（已在 debianRefPkgs 探测安装），此处生成 zh_CN.UTF-8
    # locale。任一步失败不阻断（仅影响中文）。
    locale-gen zh_CN.UTF-8 2>/dev/null \
      || { sed -i 's/^#[[:space:]]*\(zh_CN.UTF-8\)/\1/' /etc/locale.gen && locale-gen; } \
      || echo "警告: locale-gen zh_CN.UTF-8 失败（继续）"
    update-locale LANG=zh_CN.UTF-8 2>/dev/null || true
    echo "构建机 locale 就绪: $(locale -a 2>/dev/null | grep -i zh_cn | head -1 || echo 'zh_CN 未生成')"
    sed -i 's/^#[[:space:]]*\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  *)
    echo "不支持的发行版 => ${ID:-未知}"
    exit 1
    ;;
  esac

  # ============================================================
  # bionic-box64 走 NDK clang（与 rootfs 其它包同链）。patchelf/file
  # 已按参考 build-box64.yml 装上（供 glibc 形态工作流 build-box64.yml
  # 的 PT_INTERP/DT_RPATH 改写与 ELF 校验消费）；bionic 配方链依旧
  # 无需任何交叉 glibc 工具链。
  # ============================================================
  echo "工具链就绪: NDK 交叉编译（bionic），宿主依赖已按参考 yml 对齐"
else
  echo "不支持的发行版 => ${ID:-未知}"
  exit 1
fi
