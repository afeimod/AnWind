revision="1.28.6"
url="https://github.com/GStreamer/gstreamer.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="LGPL-2.1"

# ⚠️ args 字符串内禁止写注释：框架 meson setup 以无引号展开传递 ${args}，
#    注释行会变成垃圾位置参数；且注释里的引号会提前闭合 args="..."，
#    导致整个赋值丢失（v2.29 曾因此 args 全空）。说明一律写在本块外。

# gl 必开：wine/Proton 的 configure 用 pkg-config 检查 gstreamer-gl-1.0
# （proton_10.0 的 GSTREAMER 检查为 gstreamer-1.0 video audio tag gl 五件套，
#   缺任一 .pc 则整体失败 → gst/gst.h 找不到 →
#   configure 报 gstreamer-1.0 base plugins development files not found。
#   gl=disabled 时 gstreamer-gl-1.0.pc 不会生成，这正是此前 proton
#   arm64ec/x86_64 构建失败的原因。依赖 mesa 提供的 GL/EGL 头与 libGL/libEGL。）
args="
  --wrap-mode=nodownload
  -Dgst-full-target-type=shared_library
  -Dintrospection=disabled
  -Dgst-full-libraries=app,video,player
  -Dorc=enabled
  -Dorc-source=system
  -Dorc-compiler=disabled
  -Dbase=enabled
  -Dgood=enabled
  -Dbad=enabled
  -Dugly=enabled
  -Dlibav=enabled
  -Dtests=disabled
  -Dexamples=disabled
  -Ddoc=disabled
  -Dges=disabled
  -Dpython=disabled
  -Ddevtools=disabled
  -Drtsp_server=disabled
  -Dlibnice=disabled
  -Dgtk=disabled
  -Dgst-examples=disabled
  -Dgstreamer:check=disabled
  -Dgstreamer:benchmarks=disabled
  -Dgstreamer:libunwind=disabled
  -Dgstreamer:libdw=disabled
  -Dgstreamer:bash-completion=disabled
  -Dgst-plugins-base:tcp=disabled
  -Dgst-plugins-base:typefind=disabled
  -Dgst-plugins-base:gio=disabled
  -Dgst-plugins-base:examples=disabled
  -Dgst-plugins-base:alsa=enabled
  -Dgst-plugins-base:pango=disabled
  -Dgst-plugins-base:x11=enabled
  -Dgst-plugins-base:gl=enabled
  -Dgst-plugins-base:opus=enabled
  -Dgst-plugins-good:cairo=disabled
  -Dgst-plugins-good:gdk-pixbuf=disabled
  -Dgst-plugins-good:oss=disabled
  -Dgst-plugins-good:oss4=disabled
  -Dgst-plugins-good:v4l2=disabled
  -Dgst-plugins-good:aalib=disabled
  -Dgst-plugins-good:jack=disabled
  -Dgst-plugins-good:pulse=enabled
  -Dgst-plugins-good:adaptivedemux2=disabled
  -Dgst-plugins-good:libcaca=disabled
  -Dgst-plugins-good:mpg123=enabled
  -Dgst-plugins-good:multifile=disabled
  -Dgst-plugins-good:rtp=disabled
  -Dgst-plugins-good:rtpmanager=disabled
  -Dgst-plugins-good:rtsp=disabled
  -Dgst-plugins-good:soup=disabled
  -Dgst-plugins-good:udp=disabled
  -Dgst-plugins-good:vpx=enabled
  -Dgst-plugins-bad:androidmedia=disabled
  -Dgst-plugins-bad:rtmp=disabled
  -Dgst-plugins-bad:shm=disabled
  -Dgst-plugins-bad:zbar=disabled
  -Dgst-plugins-bad:webp=disabled
  -Dgst-plugins-bad:kms=disabled
  -Dgst-plugins-bad:vulkan=disabled
  -Dgst-plugins-bad:dash=disabled
  -Dgst-plugins-bad:analyticsoverlay=disabled
  -Dgst-plugins-bad:nvcodec=disabled
  -Dgst-plugins-bad:uvch264=disabled
  -Dgst-plugins-bad:v4l2codecs=disabled
  -Dgst-plugins-bad:udev=disabled
  -Dgst-plugins-bad:libde265=enabled
  -Dgst-plugins-bad:smoothstreaming=disabled
  -Dgst-plugins-bad:fluidsynth=disabled
  -Dgst-plugins-bad:inter=disabled
  -Dgst-plugins-bad:x11=enabled
  -Dgst-plugins-bad:gl=disabled
  -Dgst-plugins-bad:wayland=disabled
  -Dgst-plugins-bad:openh264=disabled
  -Dgst-plugins-bad:hip=disabled
  -Dgst-plugins-bad:aja=disabled
  -Dgst-plugins-bad:aes=disabled
  -Dgst-plugins-bad:dtls=disabled
  -Dgst-plugins-bad:hls=disabled
  -Dgst-plugins-bad:curl=disabled
  -Dgst-plugins-bad:opus=disabled
  -Dgst-plugins-bad:webrtc=disabled
  -Dgst-plugins-bad:webrtcdsp=disabled
  -Dgst-plugins-bad:debugutils=disabled
  -Dgst-plugins-bad:librfb=disabled
  -Dgst-plugins-bad:rist=disabled
  -Dgst-plugins-bad:rtmp2=disabled
  -Dgst-plugins-bad:rtp=disabled
  -Dgst-plugins-bad:srtp=disabled
  -Dgst-plugins-bad:sdp=disabled
  -Dgst-plugins-bad:tensordecoders=disabled
  -Dgst-plugins-bad:unixfd=disabled
  -Dgst-plugins-bad:fdkaac=enabled
  -Dgst-plugins-bad:cuda-nvmm=disabled
  -Dgst-plugins-ugly:asfdemux=enabled
  -Dpackage-origin=anwind-bionic-rootfs
"
# mesa：gst-plugins-base 的 gl 库需要 GL/gl.h、GLES2、EGL/egl.h 头与
# libGL/libEGL（均由 mesa 配方装入 prefix），必须先于 gstreamer 构建
deps="libandroid-shmem alsa-lib libpng pulseaudio gmp pcre2 glib libogg libflac libopus libvorbis mp3lame mpg123 libdrm libcairo nettle libopus libjpeg-turbo libde265 openh264 libvpx orc ffmpeg libfdk-aac mesa xorgproto libxcb libX11 libXext libXfixes libXdamage"

pre_setup() {
  LDFLAGS+=" -landroid-shmem"
}

custom_patch() {
  # gst-plugins-base 的 gstshmallocator.c：bionic 任何 API 级别均无
  # shm_open/shm_unlink 的头文件声明（官方 status.md 列为 SELinux 禁止
  # 的 missing functions），而 meson 的 has_function('shm_open') 是
  # 链接测试 —— pre_setup 已把 -landroid-shmem 注入 c_link_args，
  # libandroid-shmem 在链接期提供符号 → 检查通过并给编译行带上
  # -DHAVE_SHM_OPEN；但编译期 <sys/mman.h> 无声明，clang 的
  # -Wimplicit-function-declaration 直接判错（CI wine x86_64/arm64ec
  # 与 rootfs aarch64 三条流水线均折在 [122/1741] 此文件）。
  # 按 wine/hangover-wine 配方的同款方案（ANWIND_SHM_COMPAT 标记幂等）
  # 注入原型，符号由既有 -landroid-shmem 解析（memfd_create 直调优先，
  # ashmem 兜底）。gst-plugins-bad 的 shm 插件已 -Dshm=disabled，其
  # shmpipe.c 不参与编译，无需处理。
  local _srcBase="${pkgSrcDir:-${srcDir}/${pjName}}"
  local _f="${_srcBase}/subprojects/gst-plugins-base/gst-libs/gst/allocators/gstshmallocator.c"
  if [[ ! -f "${_f}" ]]; then
    echo "未找到 gstshmallocator.c（源码树结构变化），跳过 shm 原型注入"
    return 0
  fi
  if grep -q "ANWIND_SHM_COMPAT" "${_f}"; then
    echo "shm 原型已注入过，跳过 => gstshmallocator.c"
    return 0
  fi
  cat > "${_srcBase}/.anwind-shm-compat.h" <<'SHMDECL'
/* ANWIND_SHM_COMPAT: bionic 无 POSIX shm_open/shm_unlink 声明（官方
   status.md 列为 SELinux 禁止，任何 API 级别均无）。实现由
   libandroid-shmem 提供（memfd_create 直调优先，ashmem 兜底），链接经
   LDFLAGS 中的 -landroid-shmem 解析；此块仅补原型，
   避免 clang 隐式函数声明被判错。 */
#include <sys/types.h>
extern int shm_open(const char *name, int oflag, mode_t mode);
extern int shm_unlink(const char *name);
SHMDECL
  cat "${_srcBase}/.anwind-shm-compat.h" "${_f}" > "${_f}.anwind-tmp" \
    && mv "${_f}.anwind-tmp" "${_f}" \
    && echo "已注入 shm_open/shm_unlink 原型 => gstshmallocator.c"
  rm -f "${_srcBase}/.anwind-shm-compat.h"
}