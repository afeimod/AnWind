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
  # -landroid: NDK <android/trace.h> 的 ATrace_*（供下方 cutils/trace.h shim 引用）
  LDFLAGS+=" -landroid-shmem -llog -landroid"
  # ANDROID_API_LEVEL：上游仅在 platforms=android 时经 meson 注入该宏，
  # 但 util/os_misc.c 的 DETECT_OS_ANDROID 分支在 x11 平台同样引用它
  # （26.1.0：#if ANDROID_API_LEVEL >= 26 内重定义 PROPERTY_KEY_MAX，缺失即编译失败；
  #   26.2.1：同样条件内重定义 PROP_NAME_MAX，缺失仅静默退化但仍应补齐）
  CFLAGS+=" -DANDROID_API_LEVEL=${api}"
  CXXFLAGS+=" -DANDROID_API_LEVEL=${api}"
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
  # ---- NDK 平台私有头 shim（mesa ≤26.1 系必需；≥26.2 不再引用、保留无害）----
  # NDK r28b 实测缺失（仅有 <android/log.h>/<android/trace.h>/<sys/system_properties.h>）：
  #   log/log.h            <- mesa ≤26.1 的 util/os_misc.c（DETECT_OS_ANDROID 分支），
  #                           且用到平台宏 LOG_PRI -> __android_log_print
  #   cutils/log.h         <- HAVE_ANDROID_PLATFORM 路径（egllog.c 等）的保险转发
  #   cutils/properties.h  <- mesa ≤26.1 的 os_misc.c 读 Android 系统属性
  #                           （property_get 平台 API，NDK 无声明无实现）
  #   cutils/trace.h       <- mesa ≤26.1 的 perf/cpu_trace.h（Perfetto 关闭 +
  #                           DETECT_OS_ANDROID 时 u_format/disk_cache/compress/
  #                           vdrm 等 C 文件全部可及；atrace_* 平台 API，
  #                           NDK 只有 ATrace_*，须转发实现）
  # 不补齐则 os_misc.c 首先编译失败（CI 实证：fatal error: 'log/log.h' file not found）
  mkdir -p "${prefix}/include/log"
  cat > "${prefix}/include/log/log.h" <<'ANWIND_SHIM'
/* AnWind bionic rootfs shim: NDK 仅提供 <android/log.h>，无平台私有头
 * <log/log.h>。转发 + 补 mesa 实际用到的 LOG_PRI 宏（liblog 链接由 -llog 提供）。 */
#pragma once
#include <android/log.h>
#ifndef LOG_PRI
#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
#endif
ANWIND_SHIM
  cat > "${prefix}/include/cutils/log.h" <<'ANWIND_SHIM'
/* AnWind bionic rootfs shim: 平台私有头 <cutils/log.h> 的保险转发
 * （mesa HAVE_ANDROID_PLATFORM 路径引用；NDK 无此头）。 */
#pragma once
#include <android/log.h>
#ifndef LOG_PRI
#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
#endif
ANWIND_SHIM
  cat > "${prefix}/include/cutils/properties.h" <<'ANWIND_SHIM'
/* AnWind bionic rootfs shim: NDK 无 <cutils/properties.h>（property_get 为
 * 平台 API）。基于 NDK 自带 __system_property_get 实现只读包装；
 * property_set 在应用沙箱无权限，置为 no-op。 */
#pragma once
#include <string.h>
#include <strings.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#ifndef PROPERTY_KEY_MAX
#define PROPERTY_KEY_MAX 32
#endif
#ifndef PROPERTY_VALUE_MAX
#define PROPERTY_VALUE_MAX 92
#endif
static inline int property_get(const char *key, char *value, const char *default_value) {
    int len = __system_property_get(key, value);
    if (len <= 0 && default_value != NULL) {
        len = (int)strlen(default_value);
        memcpy(value, default_value, (size_t)len + 1);
    }
    return len;
}
static inline int property_get_bool(const char *key, int default_value) {
    char buf[PROPERTY_VALUE_MAX];
    int len = property_get(key, buf, "");
    if (len > 0) {
        if (!strcmp(buf, "1") || !strcasecmp(buf, "true")) return 1;
        if (!strcmp(buf, "0") || !strcasecmp(buf, "false")) return 0;
    }
    return default_value;
}
static inline int property_get_int32(const char *key, int default_value) {
    char buf[PROPERTY_VALUE_MAX];
    int len = property_get(key, buf, "");
    return len > 0 ? (int)strtol(buf, NULL, 0) : default_value;
}
static inline int property_set(const char *key, const char *value) {
    (void)key; (void)value;
    return -1;
}
ANWIND_SHIM
  cat > "${prefix}/include/cutils/trace.h" <<'ANWIND_SHIM'
/* AnWind bionic rootfs shim: NDK 无 <cutils/trace.h>（atrace_* 为平台 API）。
 * mesa ≤26.1 的 perf/cpu_trace.h（Perfetto 关闭 + DETECT_OS_ANDROID 分支）调用
 * atrace_init/atrace_begin/atrace_end；基于 NDK <android/trace.h> 的
 * ATrace_*（API>=23）实现同签名转发，符号位于 libandroid（-landroid）。 */
#pragma once
#include <stdint.h>
#include <android/trace.h>
#define ATRACE_TAG_NEVER 0
#define ATRACE_TAG_ALWAYS (1 << 0)
#define ATRACE_TAG_GRAPHICS (1 << 1)
#define ATRACE_TAG_INPUT (1 << 2)
#define ATRACE_TAG_VIEW (1 << 3)
#define ATRACE_TAG_WEBVIEW (1 << 4)
#define ATRACE_TAG_WINDOW_MANAGER (1 << 5)
#define ATRACE_TAG_ACTIVITY_MANAGER (1 << 6)
#define ATRACE_TAG_SYNC_MANAGER (1 << 7)
#define ATRACE_TAG_AUDIO (1 << 8)
#define ATRACE_TAG_VIDEO (1 << 9)
#define ATRACE_TAG_CAMERA (1 << 10)
#define ATRACE_TAG_HAL (1 << 11)
#define ATRACE_TAG_APP (1 << 12)
#define ATRACE_TAG_RESOURCES (1 << 13)
#define ATRACE_TAG_DALVIK (1 << 14)
#define ATRACE_TAG_RS (1 << 15)
#define ATRACE_TAG_BIONIC (1 << 16)
#define ATRACE_TAG_POWER (1 << 17)
#define ATRACE_TAG_PACKAGE_MANAGER (1 << 18)
#define ATRACE_TAG_SYSTEM_SERVER (1 << 19)
#define ATRACE_TAG_DATABASE (1 << 20)
#define ATRACE_TAG_NETWORK (1 << 21)
#define ATRACE_TAG_ADB (1 << 22)
#define ATRACE_TAG_VIBRATOR (1 << 23)
#define ATRACE_TAG_AIDL (1 << 24)
#define ATRACE_TAG_NDN (1 << 25)
#define ATRACE_TAG_NOTIFICATION_MANAGER (1 << 26)
#define ATRACE_TAG_RENDERER (1 << 27)
#define ATRACE_TAG_COMPRESSION (1 << 28)
static inline int atrace_is_tag_enabled(uint64_t tag) {
    return tag != ATRACE_TAG_NEVER && ATrace_isEnabled();
}
static inline void atrace_begin(uint64_t tag, const char *name) {
    if (atrace_is_tag_enabled(tag)) ATrace_beginSection(name);
}
static inline void atrace_end(uint64_t tag) {
    if (atrace_is_tag_enabled(tag)) ATrace_endSection();
}
static inline void atrace_init(void) {
}
ANWIND_SHIM
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
