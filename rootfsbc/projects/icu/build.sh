# icu4c
revision="78.3"
url="https://github.com/unicode-org/icu/releases/download/release-${revision}/icu4c-${revision}-sources.tgz"
urlType="tar"
license="Unicode-DFS-2016"
pkgSrcDir="${srcDir}/icu/source"
arch="aarch64 x86_64"
buildSys="autotools"
args="--disable-samples --disable-tests"
custom_configure() {
  # 参考 wayland/wine 的宿主工具方式：主机 ICU 构建目录持久保存到 host_tools/icu，
  # 交叉编译通过 --with-cross-build 引用，避免 get_src 清空 src 后每次重建主机 ICU
  local hostTools="${wsDir}/host_tools/icu"
  local hostBuildDir="${hostTools}/host_build"
  # 版本一致才跳过宿主机构建
  local hostVer=""
  if [[ -f "${hostTools}/.icu-version" ]]; then
    hostVer="$(cat "${hostTools}/.icu-version")"
  fi
  if [[ "$hostVer" != "$revision" || ! -f "${hostBuildDir}/config/icucross.mk" ]]; then
    echo "构建宿主机 ICU..."
    load_env_host
    rm -rf "${hostTools}"
    mkdir -p "${hostBuildDir}"
    cd "${hostBuildDir}" || { err "ICU: cd host_build 失败" && return 1; }
    "${pkgSrcDir}/runConfigureICU" Linux/gcc || { err "ICU: 宿主机 configure 失败" && return 1; }
    make -j$(nproc) || { err "ICU: 宿主机构建失败" && return 1; }
    echo "$revision" > "${hostTools}/.icu-version"
  fi
  load_env "$targetArch"
  cd "${pkgSrcDir}" || { err "ICU: cd pkgSrcDir 失败" && return 1; }
  mkdir -p "${targetArch}_build"
  cd "${targetArch}_build" || { err "ICU: cd ${targetArch}_build 失败" && return 1; }
  ../configure ${configureBaseArgs[@]} ${args[@]} --with-cross-build="${hostBuildDir}" || configure_err
}