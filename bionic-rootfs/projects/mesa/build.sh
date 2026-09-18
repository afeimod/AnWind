revision="${ANWIND_MESA_REF:-mesa-26.2.1}"
url="https://gitlab.freedesktop.org/mesa/mesa.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
agreementTargetFile="${srcDir}/mesa/docs/license.rst"
args="
  --wrap-mode=nofallback
  -Dplatform-sdk-version=${api}
  -Dplatforms=x11
  -Degl-native-platform=x11
  -Dglx=dri
  -Dshader-cache=enabled
  -Dgles1=disabled
  -Dgles2=enabled
  -Dopengl=true
  -Dgbm=enabled
  -Degl=enabled
  -Dglvnd=disabled
  -Dllvm=disabled
  -Dlibunwind=disabled
  -Dmicrosoft-clc=disabled
  -Dvulkan-beta=true
  -Dvideo-codecs=all
  -Dmediafoundation-codecs=all
"
deps="libandroid-shmem libc++ libexpat libdrm zstd xorgproto libxau libxdmcp xcb-proto libxcb xtrans libX11 libXext libXfixes libXdamage libXxf86vm libxshmfence vulkan-headers vulkan-icd-loader"

pre_setup() {
  LDFLAGS+=" -landroid-shmem"
  # 按架构追加 gallium/vulkan 驱动（追加在 args 末尾，meson 对重复
  # -D 选项取最后值，因此多架构循环下后构建的架构参数总是生效）：
  #   aarch64 -> swrast + freedreno（Adreno 硬件加速，MSM/KGSL 双 KMD）
  #              + zink（OpenGL-over-Vulkan，配合 turnip 提升游戏兼容性，
  #              anwind-container 可 GALLIUM_DRIVER=zink 启用）
  #              与 AnWind glibc mesa 工作流保持一致
  #   x86_64  -> swrast 软件渲染
  if [[ "$targetArch" == "aarch64" ]]; then
    args+=" -Dgallium-drivers=swrast,freedreno,zink -Dvulkan-drivers=freedreno -Dfreedreno-kmds=msm,kgsl"
  else
    args+=" -Dgallium-drivers=swrast -Dvulkan-drivers="
  fi
  if (( $api <= 29 )); then
    echo "api小于29,将应用tls符号修复补丁"
    cd "${srcDir}/mesa"
    patch -p1 < "${wsDir}/projects/mesa/0011-lld-undefined-version.diff"
  fi
}
