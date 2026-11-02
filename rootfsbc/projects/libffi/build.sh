revision="v3.7.1"
url="https://github.com/libffi/libffi.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="autotools"
license="MIT"
args="
  --disable-docs
  --disable-multi-os-directory
"
custom_configure() {
  if [[ ! -f configure ]]; then
    echo "configure 不存在，尝试运行 autogen.sh"
    if [[ -f autogen.sh ]]; then
      ./autogen.sh || { err "libffi: autogen.sh 失败" && return 1; }
    else
      err "libffi: autogen.sh 不存在，无法生成 configure"
      return 1
    fi
  fi
  ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
  local _include_dir="$(ls -d *-linux-android)"
  if [[ ! -f ${_include_dir}/fficonfig.h ]]; then
    err "libffi: fficonfig.h 未生成"
    return 1
  fi
  echo "#define FFI_MMAP_EXEC_WRIT 1" >> ${_include_dir}/fficonfig.h
}