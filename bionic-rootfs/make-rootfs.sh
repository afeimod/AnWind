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

[[ ${#pkgFiles[@]} -eq 0 ]] && {
  echo "::error::未找到匹配的包 (arch=${targetArch})"
  echo "pkgs 目录内容（前 20 项）:"
  ls -lh "${pkgDir}" 2>/dev/null | head -20 || true
  exit 1
}

# ---- 磁盘空间预检：解压目标需要至少与包体积相当的空间 ----
# 注意：du/df 输出必须校验为纯数字后才参与比较，避免诊断脚本本身
# 因异常输入（空值/非数字）产生二次错误或误判。
needKB="$(du -sk "${pkgDir}" | cut -f1 | tr -d '[:space:]')"
freeKB="$(df -Pk "$exportPath" | awk 'NR==2{print $4}' | tr -d '[:space:]')"
if [[ "$needKB" =~ ^[0-9]+$ && "$freeKB" =~ ^[0-9]+$ ]]; then
  echo "包总计: $((needKB / 1024))MB (${#pkgFiles[@]} 个), 解压目标可用: $((freeKB / 1024))MB"
  if [[ "$freeKB" -lt "$needKB" ]]; then
    echo "::error::磁盘空间不足：解压需约 $((needKB / 1024))MB，仅剩 $((freeKB / 1024))MB"
    echo "提示：请确认组装步骤已清理 src/、/data/data 前缀、/tmp/build-* 等构建残留。"
    df -h "$exportPath" | tail -2
    exit 2
  fi
else
  echo "警告: 磁盘预检数值异常 (needKB='${needKB}' freeKB='${freeKB}')，跳过预检继续组装"
fi

echo "解压 ${#pkgFiles[@]} 个包到 ${exportPath} (arch=${targetArch})"

# ---- 逐包解压：单包失败不中断，最后汇总报告（便于一眼定位问题包） ----
# 已成功解压的包立即删除：组装峰值占用从 (pkgs+导出) 降为 (pkgs+单包)，
# 避免导出与包本体双份占满磁盘；失败的包保留在 pkgs/ 便于诊断。
errFile="/tmp/.make-rootfs-err.$$"
failedPkgs=()
for pkg in "${pkgFiles[@]}"; do
  echo "解压 => $(basename "$pkg")"
  if tar -xf "$pkg" -C "$exportPath" 2>"$errFile"; then
    rm -f -- "$pkg"
  else
    echo "::error::解压失败 => $(basename "$pkg")"
    # 注意顺序：head 先截断再交给 sed，避免 sed 在长错误清单下触发 SIGPIPE/pipefail
    head -15 "$errFile" | sed 's/^/    /' || true
    failedPkgs+=("$(basename "$pkg")")
  fi
done
rm -f "$errFile"

if [[ ${#failedPkgs[@]} -gt 0 ]]; then
  echo "::error::共 ${#failedPkgs[@]} 个包解压失败: ${failedPkgs[*]}"
  echo "=== 解压目标磁盘状态 ==="
  df -h "$exportPath" | tail -2
  exit 2
fi

# ---- 绝对符号链接重写：让导出树自包含 ----
# meson 的 install_symlink（如 xkeyboard-config compat-rules 的
# share/X11/xkb -> <prefix>/share/xkeyboard-config-2）会生成指向设备最终
# 前缀 /data/data/com.anwind/files/rootfs 的绝对符号链接。该前缀在构建机
# 上并不存在（组装前已清理），导出树内的这类链接全部为断链：产物校验的
# [[ -e ]] 跟随链接会误判缺失，设备侧也被迫依赖固定挂载路径。这里把
# "指向导出树自身"的绝对链接统一改写为相对链接；指向树外（App 侧前缀
# 等）的链接不在导出树内、不属于 rootfs 自身内容，保持原样。
rfsPrefix="/data/data/com.anwind/files/rootfs"
while IFS= read -r -d '' link; do
  tgt="$(readlink -- "$link")" || continue
  case "$tgt" in
    "$rfsPrefix" | "$rfsPrefix"/*) ;;
    *) continue ;;
  esac
  absTgt="${exportPath}${tgt}"
  if [[ ! -e "$absTgt" && ! -L "$absTgt" ]]; then
    echo "警告: 符号链接目标不在导出树内，保持原样: ${link#"$exportPath"/} -> $tgt"
    continue
  fi
  newTgt="$(realpath -m --relative-to="$(dirname -- "$link")" "$absTgt")" || {
    echo "警告: 无法计算相对路径（保留原样）: ${link#"$exportPath"/}"
    continue
  }
  if ln -snf -- "$newTgt" "$link" 2>/dev/null; then
    echo "重写绝对符号链接 => ${link#"$exportPath"/}: '$tgt' -> '$newTgt'"
  else
    echo "警告: 符号链接重写失败（保留原样）: ${link#"$exportPath"/}"
  fi
done < <(find "$exportPath" -type l -print0)

echo "=== 组装完成概览 ==="
du -sh "$exportPath" 2>/dev/null || true
echo "完成"
