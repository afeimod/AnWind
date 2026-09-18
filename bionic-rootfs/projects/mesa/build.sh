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
  LDFLAGS+=" -landroid-shmem -llog"
  # NDK 不提供平台私有头 cutils/native_handle.h / vndk/hardware_buffer.h 等，
  # 但 mesa 的 vulkan 工具层与 turnip（freedreno vulkan）在 __ANDROID__ 目标上
  # 会包含它们。-Dandroid-stub 又要求 platforms=android，x11 平台不可用；
  # 直接把 mesa 自带 android_stub 中 NDK 缺失的子集安装到 prefix include
  # （编译命令均含 -I<prefix>/include，__ANDROID__ 目标即可解析）。
  # 注意：不整体覆盖 android/（NDK 自带版本更完整），仅补 NDK 缺失的平台私有头。
  for _stubSub in cutils vndk; do
    mkdir -p "${prefix}/include/${_stubSub}"
    cp -a "${srcDir}/mesa/include/android_stub/${_stubSub}/." \
          "${prefix}/include/${_stubSub}/"
  done
  # 按架构追加 gallium/vulkan 驱动（追加在 args 末尾，meson 对重复
  # -D 选项取最后值，因此多架构循环下后构建的架构参数总是生效）：
  #   aarch64 -> softpipe + freedreno（Adreno 硬件加速，MSM/KGSL 双 KMD）
  #              + zink（OpenGL-over-Vulkan，配合 turnip 提升游戏兼容性，
  #              anwind-container 可 GALLIUM_DRIVER=zink 启用）
  #              与 AnWind glibc mesa 工作流保持一致
  #   x86_64  -> softpipe 软件渲染（mesa 25.1+ 移除 swrast；llvmpipe 需 LLVM，交叉构建不启用）
  if [[ "$targetArch" == "aarch64" ]]; then
    args+=" -Dgallium-drivers=softpipe,freedreno,zink -Dvulkan-drivers=freedreno -Dfreedreno-kmds=msm,kgsl"
    # turnip/vk_android 需要 AHardwareBuffer 系列（libnativewindow，NDK API>=26 自带）；
    # platforms=x11 下 meson 不会主动链接它
    LDFLAGS+=" -lnativewindow"
  else
    args+=" -Dgallium-drivers=softpipe -Dvulkan-drivers="
  fi
  if (( $api <= 29 )); then
    echo "api小于29,将应用tls符号修复补丁"
    cd "${srcDir}/mesa"
    patch -p1 < "${wsDir}/projects/mesa/0011-lld-undefined-version.diff"
  fi
}
