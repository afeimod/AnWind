#!/usr/bin/env bash
set -euo pipefail

wsDir="$(dirname "$(readlink -f "$0")")"
pkgDir="${wsDir}/pkgs"

exportPath=""
targetArch=""

usage() {
  echo "用法: $0 --export <目标路径> --arch <架构>"
  echo "选项:"
  echo "  --export <路径>    指定解压目标路径"
  echo "  --arch <架构>      指定架构 (aarch64|x86_64)"
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
    [[ -z "${2:-}" ]] && { echo "错误: --export 需要指定目标路径"; exit 1; }
    exportPath="$2"
    shift 2
    ;;
  --arch)
    [[ -z "${2:-}" ]] && { echo "错误: --arch 需要指定架构"; exit 1; }
    targetArch="$(normalize_arch "$2")" || { echo "错误: 不支持的架构 => $2 (仅支持 aarch64|x86_64)"; exit 1; }
    shift 2
    ;;
  --help | -h)
    usage
    ;;
  *)
    echo "未知选项: $1"
    usage
    ;;
  esac
done

[[ -z "$exportPath" ]] && { echo "错误: 必须指定 --export <目标路径>"; usage; }
[[ -z "$targetArch" ]] && { echo "错误: 必须指定 --arch <架构>"; usage; }
[[ ! -d "$pkgDir" ]] && { echo "错误: 包目录不存在 => $pkgDir"; exit 1; }

mkdir -p "$exportPath"

shopt -s nullglob
pkgFiles=("${pkgDir}"/*-"${targetArch}".tar)
shopt -u nullglob

[[ ${#pkgFiles[@]} -eq 0 ]] && { echo "未找到匹配的包 (arch=${targetArch})"; exit 1; }

echo "解压 ${#pkgFiles[@]} 个包到 ${exportPath} (arch=${targetArch})"

for pkg in "${pkgFiles[@]}"; do
  echo "解压 => $(basename "$pkg")"
  tar -xf "$pkg" -C "$exportPath"
done

echo "完成"
