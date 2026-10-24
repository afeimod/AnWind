#!/bin/bash
set -euo pipefail

wsDir="$(dirname "$(readlink -f "$0")")"
pkgDir="${wsDir}/pkgs"
. "${wsDir}/utils/log.sh"
. "${wsDir}/utils/notices.sh"

# 固定 UTC：解压/校验时的时间处理不随构建者时区变化
export TZ=UTC

exportPath=""
targetArch=""
liteMode=false

usage() {
  echo "用法: $0 --export <目标路径> --arch <架构> [--lite]"
  echo "选项:"
  echo "  --export <路径>    指定解压目标路径"
  echo "  --arch <架构>      指定架构 (aarch64|x86_64)"
  echo "  --lite             移除头文件/静态库/文档等仅编译期使用的文件"
  echo "  --help             显示此帮助信息"
  exit 0
}

normalize_arch() {
  case "$1" in
  arm64) echo "aarch64" ;;
  amd64) echo "x86_64" ;;
  aarch64|x86_64) echo "$1" ;;
  *) return 1 ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --export)
    [[ -z "${2:-}" ]] && { err "--export 需要指定目标路径"; exit 1; }
    exportPath="$2"
    shift 2
    ;;
  --arch)
    [[ -z "${2:-}" ]] && { err "--arch 需要指定架构"; exit 1; }
    targetArch="$(normalize_arch "$2")" || { err "不支持的架构 => $2 (仅支持 aarch64|x86_64)"; exit 1; }
    shift 2
    ;;
  --lite)
    liteMode=true
    shift
    ;;
  --help | -h)
    usage
    ;;
  *)
    err "未知选项: $1"
    usage
    ;;
  esac
done

[[ -z "$exportPath" ]] && { err "必须指定 --export <目标路径>"; usage; }
[[ -z "$targetArch" ]] && { err "必须指定 --arch <架构>"; usage; }
[[ ! -d "$pkgDir" ]] && { err "包目录不存在 => $pkgDir"; exit 1; }

mkdir -p "$exportPath"

shopt -s nullglob
pkgFiles=("${pkgDir}"/*-"${targetArch}".tar)
shopt -u nullglob

[[ ${#pkgFiles[@]} -eq 0 ]] && { err "未找到匹配的包 (arch=${targetArch})"; exit 1; }

# @package 同名包多 revision 去重：每个项目只解压最新的 tar
# 包文件名为 <pjName>-<revision>-<arch>.tar，pjName 与 revision 都可能含 '-'，
# 因此用 projects/ 下已知项目名做最长前缀匹配分组
_pjNames=()
for _dir in "${wsDir}/projects"/*/; do
  [[ -d "$_dir" ]] && _pjNames+=("$(basename "$_dir")")
done
# 按项目名长度降序排列，保证最长前缀匹配（如 wine 与 wine-amd64）
IFS=$'\n' _pjNames=($(printf '%s\n' "${_pjNames[@]}" | awk '{print length, $0}' | sort -rn | cut -d' ' -f2-))
unset IFS

_groupOf() {
  local _base="${1%-${targetArch}.tar}"
  local _pj
  for _pj in "${_pjNames[@]}"; do
    [[ "$_base" == "${_pj}-"* ]] && { echo "$_pj"; return 0; }
  done
  # 未匹配到已知项目（如 legacy 包）：回退为去掉末段 revision 的名字
  echo "${_base%-*}"
}

declare -A _latestByPj_=()
for pkg in "${pkgFiles[@]}"; do
  _key="$(_groupOf "$(basename "$pkg")")"
  _sep=""
  [[ -n "${_latestByPj_[${_key}]:-}" ]] && _sep=$'\n'
  _latestByPj_["${_key}"]+="${_sep}${pkg}"
done

pkgFiles=()
for _key in "${!_latestByPj_[@]}"; do
  _count=$(sort -uV <<< "${_latestByPj_[${_key}]}" | wc -l)
  _latest="$(sort -uV <<< "${_latestByPj_[${_key}]}" | tail -n 1)"
  [[ "$_count" -gt 1 ]] && warn "包 ${_key} 存在多个 revision, 使用最新 => $(basename "$_latest")"
  pkgFiles+=("$_latest")
done
# 保持与文件名排序一致的解压顺序，确保结果可复现
IFS=$'\n' pkgFiles=($(printf '%s\n' "${pkgFiles[@]}" | sort))
unset IFS

# @lite
# 运行时不需要编译期产物：头文件、静态库、*.la、pkg-config/cmake 元数据、
# aclocal/libtool 宏、man/doc/info、vala/gdb 支持文件、vulkan 注册表等。
# 只裁剪导出目录，pkgs/ 下的包保持完整，随时可以重新导出非 lite 版本。
slim_rootfs() {
  local _root="$1"
  local _removed=0
  local _path
  local _file

  local _dirs=(
    usr/include
    usr/man
    usr/share/man
    usr/share/doc
    usr/share/info
    usr/share/aclocal
    usr/share/libtool
    usr/share/cmake
    usr/share/pkgconfig
    usr/share/gir-1.0
    usr/share/vala
    usr/share/gdb
    usr/share/gettext
    usr/share/wayland-protocols
    usr/share/icu
    usr/share/xml
    usr/share/ffmpeg/examples
    usr/share/vulkan/registry
    usr/lib/pkgconfig
    usr/lib/cmake
    usr/lib/icu
  )
  for _path in "${_dirs[@]}"; do
    [[ -e "${_root}/${_path}" ]] || continue
    info "移除编译期目录 => ${_path}"
    rm -rf "${_root:?}/${_path}"
    _removed=$((_removed + 1))
  done

  # 静态库与 libtool 归档只用于链接，运行时不会加载
  while IFS= read -r -d '' _file; do
    rm -f "${_file}"
    _removed=$((_removed + 1))
  done < <(find "${_root}/usr" -type f \( -name '*.a' -o -name '*.la' \) -print0)

  # *-config 与 libtool 只服务于 configure/编译
  local _configs=()
  shopt -s nullglob
  _configs=("${_root}"/usr/bin/*-config "${_root}"/usr/bin/libtool*)
  shopt -u nullglob
  for _file in "${_configs[@]}"; do
    info "移除编译期工具 => ${_file#"${_root}/"}"
    rm -f "${_file}"
    _removed=$((_removed + 1))
  done

  info "精简完成: 移除 ${_removed} 项"
}

info "解压 ${#pkgFiles[@]} 个包到 ${exportPath} (arch=${targetArch})"

for pkg in "${pkgFiles[@]}"; do
  info "解压 => $(basename "$pkg")"
  tar -xf "$pkg" -C "$exportPath"
done

# 读取 prefix，创建 usr-merge 标准软链接
source "${wsDir}/default.conf"
_rootfsRoot="${exportPath}${prefix%/*}"

if [[ -d "${_rootfsRoot}/usr" ]]; then
  info "创建 usr-merge 软链接: ${_rootfsRoot}"
  for _dir in bin lib sbin lib32 lib64 libexec etc var; do
    [[ -d "${_rootfsRoot}/usr/${_dir}" && ! -e "${_rootfsRoot}/${_dir}" ]] && {
      ln -sf "usr/${_dir}" "${_rootfsRoot}/${_dir}"
      info "  ${_dir} -> usr/${_dir}"
    }
  done
fi

[[ "$liteMode" == true && -d "${_rootfsRoot}/usr" ]] && slim_rootfs "${_rootfsRoot}"

# 第三方组件协议清单：写在导出目录里，确保它真的进入 rootfs（构建阶段写在
# ${prefix} 的话既不进任何 tar，也只会给宿主机留一棵空目录树）
gen_notices "${exportPath}${prefix}" "${!_latestByPj_[@]}"

ok "完成"
