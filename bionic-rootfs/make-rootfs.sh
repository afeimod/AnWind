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

# ---- 磁盘空间预检：解压目标需要至少与包体积相当的空间 ----
needKB=$(du -sk "${pkgDir}" | cut -f1)
freeKB=$(df -Pk "$exportPath" | awk 'NR==2{print $4}')
echo "包总计: $((needKB / 1024))MB, 解压目标可用: $((freeKB / 1024))MB"
if [[ "$freeKB" -lt "$needKB" ]]; then
  echo "::error::磁盘空间不足：解压需约 $((needKB / 1024))MB，仅剩 $((freeKB / 1024))MB"
  echo "提示：请在调用本脚本前清理构建源码树（src/）与下载缓存。"
  df -h "$exportPath" | tail -2
  exit 2
fi

echo "解压 ${#pkgFiles[@]} 个包到 ${exportPath} (arch=${targetArch})"

# ---- 逐包解压：单包失败不中断，最后汇总报告（便于一眼定位问题包） ----
failedPkgs=()
for pkg in "${pkgFiles[@]}"; do
  echo "解压 => $(basename "$pkg")"
  if ! tar -xf "$pkg" -C "$exportPath" 2>"/tmp/.make-rootfs-err.$$"; then
    echo "::error::解压失败 => $(basename "$pkg")"
    # 注意顺序：head 先截断再交给 sed，避免 sed 在长错误清单下触发 SIGPIPE/pipefail
    head -15 "/tmp/.make-rootfs-err.$$" | sed 's/^/    /' || true
    failedPkgs+=("$(basename "$pkg")")
  fi
done
rm -f "/tmp/.make-rootfs-err.$$"

if [[ ${#failedPkgs[@]} -gt 0 ]]; then
  echo "::error::共 ${#failedPkgs[@]} 个包解压失败: ${failedPkgs[*]}"
  echo "=== 解压目标磁盘状态 ==="
  df -h "$exportPath" | tail -2
  exit 2
fi

echo "完成"
