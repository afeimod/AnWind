revision="1.17.0"
url="https://xorg.freedesktop.org/archive/individual/lib/libxcb-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# XCB 核心 + 全部扩展库（shm/render/randr/xfixes/sync/present/dri2/dri3/
# glx/xkb/xinput/shape/damage/xv 等，随 xcb-proto 的 XML 一并生成）
deps="xcb-proto libxau libxdmcp"

pre_setup() {
  # X11 unix socket 路径对齐 AnWind 内置 X 服务：
  # libXlorie(termux-x11) 在 App 侧前缀 tmp/.X11-unix/Xn 监听
  # （默认 /data/data/com.anwind/files/usr/tmp/.X11-unix/X1，显示号 :1），
  # rootfs 内 X 客户端（wine 等）必须连这里而非 /tmp。
  # 与 default.conf 的 appPrefix 保持一致。
  sed -i "s|/tmp/.X11-unix/X|${appPrefix:-/data/data/com.anwind/files/usr}/tmp/.X11-unix/X|g" src/xcb_util.c
  grep -q "anwind\|files/usr/tmp/.X11-unix" src/xcb_util.c || { echo "xcb socket 路径补丁失败!" && exit 1; }

  # libxcb 构建时需要宿主 python 导入 xcbgen（xcb-proto 已安装到 prefix）
  local _pyDir
  _pyDir="$(ls -d "${prefix}"/lib/python3/dist-packages "${prefix}"/lib/python3*/site-packages 2>/dev/null | head -1)"
  if [[ -n "$_pyDir" ]]; then
    export PYTHONPATH="${_pyDir}:${PYTHONPATH:-}"
  else
    echo "警告: 未找到 xcbgen 所在目录（xcb-proto 未安装?）"
  fi
  echo "PYTHONPATH=${PYTHONPATH:-}"
}
