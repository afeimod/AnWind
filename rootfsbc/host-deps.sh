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

  glib2
  glib2-devel
)

debianPkgs=(
  build-essential
  git
  wget
  p7zip-full
  ccache
  cmake
  meson
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
  libgio-2.0-dev-bin
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
    pip install PyYAML --break-system-packages
    sed -i 's/^#[[:space:]]*\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  debian)
    echo "当前为debian"
    echo "需要安装 ${debianPkgs[@]}"
    echo "交互式将取消,自动完成安装"
    export DEBIAN_FRONTEND=noninteractive
    apt update || exit 1
    apt upgrade -y || exit 1
    apt install -y ${debianPkgs[@]} || exit 1
    pip install PyYAML --break-system-packages
    sed -i 's/^#[[:space:]]*\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen
    ;;
  *)
    echo "不支持的发行版 => ${ID:-未知}"
    exit 1
    ;;
  esac
else
  echo "不支持的发行版 => ${ID:-未知}"
  exit 1
fi
