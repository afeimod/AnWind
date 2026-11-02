revision="16.5.0"
url="https://github.com/KhronosGroup/glslang.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD"
#agreementTargetFile="${srcDir}/glslang/LICENSE.txt"
args="
  -DBUILD_SHARED_LIBS=ON
  -DENABLE_HLSL=OFF
  -DENABLE_OPT=OFF
"
deps="libc++"