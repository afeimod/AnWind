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
      ./autogen.sh || { echo "autogen.sh 失败" && exit 1; }
    else
      echo "autogen.sh 也不存在，无法生成 configure"
      exit 1
    fi
  fi
  ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
  local _include_dir="$(ls -d *-linux-android)"
  if [[ ! -f ${_include_dir}/fficonfig.h ]]; then
    echo "fficonfig.h未生成"
    exit 1
  fi
  echo "#define FFI_MMAP_EXEC_WRIT 1" >> ${_include_dir}/fficonfig.h
}