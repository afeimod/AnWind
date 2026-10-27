revision="${ANWIND_BOX64_REF:-v0.4.2}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"
args="-DCMAKE_BUILD_TYPE=RelWithDebInfo -DANDROID=ON -DBAD_SIGNAL=ON -DARM_DYNAREC=ON -DNO_CONF_INSTALL=ON -DNO_LIB_INSTALL=ON"

pre_setup() {
  args+=" -DCMAKE_ASM_FLAGS="
}

install() {
  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -r -p ${srcDir}/box64/build/box64 "${destDir}${prefix}/local/bin/"
}