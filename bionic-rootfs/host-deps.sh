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
  bison
  flex
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
    # 注意：ubuntu-24.04 无 `pip` 命令（只有 python3 -m pip），旧脚本用
    # `pip` 会静默失败 → meson 未安装 → 工作流里 `meson --version` 报 127
    # （本脚本未开 set -e，失败会被吞掉，因此这里显式断言）
    python3 -m pip install --break-system-packages --upgrade pip mako PyYAML || true
    python3 -m pip install --break-system-packages --upgrade meson ninja \
      || python3 -m pip install --upgrade meson ninja \
      || { echo "meson/ninja 安装失败!" && exit 1; }
    hash -r
    command -v meson >/dev/null 2>&1 || export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
    hash -r
    command -v meson >/dev/null 2>&1 || { echo "meson 仍不可用!" && exit 1; }
    echo "meson 版本: $(meson --version)"
    echo "ninja 版本: $(ninja --version)"
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
