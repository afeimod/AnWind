# v3.4.7【wowbox64 无声之 alsa 补全】（用户 2026-11-11 实证反馈：
# "你用了 nopulse 但是 alsa 没有补全依赖肯定没有声音，根本没有点用
# 声音驱动，保证正常使用 pulse 或者 nopulse 使用 alsa，查看 rootfs
# 是不是没有相关编译构建"）：
#   rootfs 此前只有 alsa-lib（wine --with-alsa → winealsa.drv 能编译），
#   但 Android 上没有 /dev/snd/* —— libasound 的默认 PCM 无任何后端
#   可用 → winealsa 一落空就无声。alsa-plugins 的 pulse 后端
#   （libasound_module_pcm_pulse.so）+ /etc/asound.conf 全局默认指向
#   pulse，winealsa 的输出经 libasound → pulse 插件 → rootfs
#   PulseAudio 守护 → module-sles-sink → Android 扬声器 —— audio=alsa
#   档从"摆设"变"实路"（anwind-wine/容器 audio=alsa 同步支持）。
#   同时也是 pulse 驱动无声时的官方备选通路（wine 注册表 Drivers
#   Audio=alsa 时 wine 只用 winealsa）。
revision="1.2.12"
url="https://www.alsa-project.org/files/pub/plugins/alsa-plugins-${revision}.tar.bz2"
# alsa-project.org 历史性宕机常见；GitHub codeload 自动归档兜底
#（顶层目录 alsa-plugins-1.2.12/，框架 --strip-components=1 兼容；
#  无 configure —— 框架 autoreconf -fi 自动生成，实测 tag v1.2.12 200）。
backupUrls=(
  "https://codeload.github.com/alsa-project/alsa-plugins/tar.gz/refs/tags/v${revision}"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-2.0-or-later"
# 只建 pulse 桥：NDK 交叉环境探测不到 jack/speexdsp/libsamplerate 等
# 宿主库（sysroot 隔离，自动关停）；显式 --disable-* 双保险（configure
# 对未知 --disable 仅警告忽略，零风险）。
args="--disable-jack --disable-usbstream --disable-samplerate --disable-speexdsp"
deps="alsa-lib pulseaudio"

install() {
  DESTDIR="${destDir}" PREFIX="${prefix}" make install
  # /etc/asound.conf：全局默认 PCM/Ctl 经 pulse 插件转给 PulseAudio
  # 守护。没有这份覆盖，libasound 默认 PCM 在 Android 无后端可用
  #（/dev/snd/* 不存在）—— winealsa 落空即无声的根因。侦听地址由
  # PULSE_SERVER 环境决定（anwind-wine audio=alsa 与 pulse 同源注入）。
  mkdir -p "${destDir}${prefix}/etc"
  cat > "${destDir}${prefix}/etc/asound.conf" <<'EOF'
# AnWind v3.4.7：winealsa 全局默认 → alsa-plugins pulse 桥
#（Android 无 /dev/snd，ALSA 用户态唯一现实出口是 pulse 守护；
#  守护地址随 PULSE_SERVER 环境走，与 winepulse 同一实例）。
pcm.!default {
    type pulse
    hint.description "PulseAudio via alsa-plugins (AnWind)"
}
ctl.!default {
    type pulse
}
EOF
}
