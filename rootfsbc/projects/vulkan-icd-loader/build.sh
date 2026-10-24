revision="v1.4.359"
url="https://github.com/KhronosGroup/Vulkan-Loader.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="Apache-2.0"
args="
  -DVULKAN_HEADERS_INSTALL_DIR=${prefix}
  -DBUILD_WSI_XCB_SUPPORT=OFF
  -DBUILD_WSI_XLIB_SUPPORT=OFF
  -DBUILD_WSI_XLIB_XRANDR_SUPPORT=OFF
  -DBUILD_WSI_WAYLAND_SUPPORT=ON
"
deps="vulkan-headers"
install() {
  cd "${srcDir}/vulkan-icd-loader"
  DESTDIR="${destDir}" cmake --install build --prefix "${prefix}"
  cd "${destDir}/${prefix}/lib"
  ln -sf libvulkan.so libvulkan.so.1
}