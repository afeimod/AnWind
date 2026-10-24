revision="v2026.3"
url="https://github.com/KhronosGroup/SPIRV-Tools.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="Apache-2.0"
args="
  -DCMAKE_INSTALL_LIBDIR=${prefix}/lib
  -DCMAKE_INSTALL_INCLUDEDIR=${prefix}/include
  -DSPIRV-Headers_SOURCE_DIR=${prefix}
  -DSPIRV_WERROR=OFF
"
deps="spirv-headers"