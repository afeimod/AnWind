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
    pip install PyYAML --break-system-package
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
    pip install PyYAML --break-system-package
    # meson 通过 pip 安装较新版本（GitHub Actions 的 ubuntu-24.04 自带
    # meson 1.3.x，部分包如新版 mesa/xorgproto 需要更高版本）
    pip install --break-system-packages --upgrade meson ninja mako PyYAML
    export PATH="$HOME/.local/bin:$PATH"
    echo "meson 版本: $(meson --version)"
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
