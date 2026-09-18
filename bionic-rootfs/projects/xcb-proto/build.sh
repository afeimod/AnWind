revision="1.17.0"
url="https://xorg.freedesktop.org/archive/individual/proto/xcb-proto-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
# XCB 协议描述（XML）+ xcbgen python 模块。
# 本包不需要任何目标架构二进制；xcbgen 供 libxcb 构建时由宿主 python 导入。
# xml/pc 文件安装到 ${prefix}/share，xcbgen 由下方 install() 钩子
# 归位到固定路径 ${prefix}/lib/python3/dist-packages/xcbgen，
# libxcb 构建时通过 PYTHONPATH 引用。
deps=""

install() {
  make install DESTDIR="${destDir}" PREFIX="${prefix}"
  # 归位 xcbgen 到确定性路径（发行版 python 的 site-packages 命名不一致，
  # python3.12 会落到 lib/python3.12/site-packages）
  mkdir -p "${destDir}${prefix}/lib/python3/dist-packages"
  local _gen
  _gen="$(find "${destDir}${prefix}/lib" -maxdepth 4 -type d -name xcbgen 2>/dev/null | head -1)"
  if [[ -n "$_gen" && "$_gen" != "${destDir}${prefix}/lib/python3/dist-packages/xcbgen" ]]; then
    rm -rf "${destDir}${prefix}/lib/python3/dist-packages/xcbgen"
    cp -a "${_gen}" "${destDir}${prefix}/lib/python3/dist-packages/xcbgen"
  fi
  # 守护：确保 xcbgen 存在，否则 libxcb 构建必然失败
  [[ -d "${destDir}${prefix}/lib/python3/dist-packages/xcbgen" ]] || {
    echo "xcbgen 归位失败!" && exit 1
  }
}
