revision="1.26.0"
url="https://gitlab.freedesktop.org/wayland/wayland.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
args="
  -Ddocumentation=false
  -Ddtd_validation=false
  -Dtests=false
  -Dscanner=false
"
deps="libffi libexpat"
pre_setup() {
  # 参考 icu 项目的主机编译方式：
  # 交叉编译前先用宿主工具链构建 wayland-scanner 并安装到 host_tools/wayland，
  # 之后 -Dscanner=false 的交叉构建会通过 pkg-config 找到宿主 scanner
  local hostTools="${wsDir}/host_tools/wayland"
  local scanner="${hostTools}/bin/wayland-scanner"
  # 只有版本一致才跳过宿主机构建
  local hostVer=""
  if [[ -x "$scanner" ]]; then
    hostVer="$("$scanner" --version 2>&1 | grep -o '[0-9]\+\.[0-9]\+\.[0-9]\+')"
  fi
  if [[ "$hostVer" != "$revision" ]]; then
    echo "构建宿主机 wayland-scanner..."
    load_env_host
    # 主机 scanner 构建需要宿主的 expat，不要用交叉环境的 pkg-config 前缀
    unset PKG_CONFIG_LIBDIR PKG_CONFIG_SYSROOT_DIR
    meson setup host_build \
      --prefix="${hostTools}" \
      -Dscanner=true \
      -Dlibraries=false \
      -Ddocumentation=false \
      -Ddtd_validation=false \
      -Dtests=false || configure_err
    meson compile -C host_build || compile_err
    meson install -C host_build || compile_err
    rm -rf host_build
    load_env "$targetArch"
  fi
  export PATH="${hostTools}/bin:$PATH"
  export PKG_CONFIG_LIBDIR="${hostTools}/lib/pkgconfig:${hostTools}/share/pkgconfig:${hostTools}/lib/x86_64-linux-gnu/pkgconfig:${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
  # 宿主 scanner 的 prefix 不在目标 sysroot 下，
  # 必须清除 sysroot，否则 pkg-config 会给出错误的 wayland_scanner 路径
  unset PKG_CONFIG_SYSROOT_DIR
}
pre_package() {
  cd "${destDir}/${prefix}/lib"
  ln -sf libwayland-client.so libwayland-client.so.0
  ln -sf libwayland-cursor.so libwayland-cursor.so.0
  ln -sf libwayland-egl.so libwayland-egl.so.1
  ln -sf libwayland-server.so libwayland-server.so.0
}