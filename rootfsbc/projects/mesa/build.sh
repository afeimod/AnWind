revision="mesa-26.2.1"
url="https://gitlab.freedesktop.org/mesa/mesa.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
agreementTargetFile="${srcDir}/mesa/docs/license.rst"
args="
  --wrap-mode=nofallback
  -Dplatform-sdk-version=${api}
  -Dandroid-stub=true
  -Dandroid-strict=false
  -Dplatforms=android
  -Degl-native-platform=android 
  -Dgallium-drivers=zink
  -Dvulkan-drivers=
  -Dshader-cache=enabled 
  -Dgles1=disabled
  -Dgles2=enabled 
  -Dopengl=true 
  -Dgbm=enabled 
  -Degl=enabled 
  -Dglx=disabled 
  -Dglvnd=disabled 
  -Dllvm=disabled 
  -Dlibunwind=disabled 
  -Dmicrosoft-clc=disabled 
  -Dvulkan-beta=true 
  -Dvideo-codecs=all 
  -Dmediafoundation-codecs=all
"
deps="libandroid-shmem libc++ libexpat libdrm zstd"

pre_setup() {
  LDFLAGS+=" -landroid-shmem"
  if (( $api <= 29 )); then
    local _patchFile="${wsDir}/projects/mesa/0011-lld-undefined-version.diff"
    cd "${srcDir}/mesa"
    # 源码会在双架构构建间复用，补丁仅在尚未应用时打，避免第二次应用失败
    if patch -p1 --dry-run < "${_patchFile}" >/dev/null 2>&1; then
      echo "api小于29,将应用tls符号修复补丁"
      patch -p1 < "${_patchFile}" || { err "mesa: 补丁应用失败" && return 1; }
    elif patch -p1 -R --dry-run < "${_patchFile}" >/dev/null 2>&1; then
      info "mesa: tls符号修复补丁已应用，跳过"
    else
      err "mesa: tls符号修复补丁无法应用"
      return 1
    fi
  fi
}
