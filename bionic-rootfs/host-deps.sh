wsDir="$(dirname $(readlink -f "$0"))"

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
  libfreetype-dev
  locales
  python3-setuptools
  python3-dev
  python3-mako
)

if [[ -f /etc/os-release ]]; then
  . /etc/os-release
  case "$ID" in
  arch)
    echo "当前为archlinux"
    echo "需要安装 ${archPkgs[@]}"
    pacman --key-init
    pacman -Syyu --noconfirm || exit 1
    pacman -S --noconfirm --needed ${archPkgs[@]} || exit 1
    pip install PyYAML --break-system-packages || python3 -m pip install PyYAML --break-system-packages || true
    sed -i 's/^#\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  debian|ubuntu)
    echo "当前为 $ID"
    echo "需要安装 ${debianPkgs[@]}"
    echo "交互式将取消自动完成安装"
    export DEBIAN_FRONTEND=noninteractive
    apt update || exit 1
    apt upgrade -y || exit 1
    apt install -y ${debianPkgs[@]} || exit 1
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
    # libtool 二进制兜底：libflac 等 autogen.sh 会 `command -v libtool`
    # （此前出现过 libtoolize 可用但 libtool 缺失、40 分钟构建到 libflac 才失败的坑。
    #  libflac 已改走 CMake 不再依赖它，此处仅尝试修复 + 警告，不再致命拦截）
    if ! command -v libtool >/dev/null 2>&1; then
      echo "警告: libtool 二进制缺失，尝试显式重装..."
      apt-get install -y --reinstall libtool || true
      command -v libtool >/dev/null 2>&1 \
        || echo "警告: libtool 仍不可用（当前配方链已不依赖；若后续新增 autogen 包需注意）"
    fi
    command -v libtool >/dev/null 2>&1 && libtool --version | head -1 || true
    sed -i 's/^#\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  *)
    echo "不支持的发行版 => $ID"
    exit 1
    ;;
  esac
else
  echo "不支持的发行版 => $ID"
  exit 1
fi
