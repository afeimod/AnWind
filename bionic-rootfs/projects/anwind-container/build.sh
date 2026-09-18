revision="1.0.0"
urlType="local"
pkgSrcDir="${wsDir}/projects/anwind-container"
arch="aarch64 x86_64"
buildSys="others"
license="MIT"

# ============================================================
# anwind-container — AnWind Wine 容器管理（bionic rootfs / X11）
# 纯脚本包：安装 anwind-container（容器管理 CLI）与 anwind-wine
# （后端感知启动器）进 rootfs。数据模型参考 Winlator/WinNative/
# MiceWine：每容器独立 WINEPREFIX + CPU 翻译后端 + 个性化环境。
#   后端: box64（x86_64 wine，主力）/ hangover（aarch64 qemu）
#         / fexcore（探测槽位）/ native（x86_64 设备）
#   显示: X11 —— AnWind 内置 X 服务（App 侧 tmp/.X11-unix/Xn，
#         默认 :1），自动探测 DISPLAY
#   音频: PulseAudio TCP 127.0.0.1:4713
# ============================================================

extra_fuction() {
  local _binDir="${destDir}${prefix}/bin"
  local _etcDir="${destDir}${prefix}/etc/anwind"
  local _profDir="${destDir}${prefix}/etc/profile.d"

  mkdir -p "${_binDir}" "${_etcDir}" "${_profDir}"

  echo "安装 anwind-container / anwind-wine ..."
  install -Dm755 "${pkgSrcDir}/anwind-container" "${_binDir}/anwind-container"
  install -Dm755 "${pkgSrcDir}/anwind-wine"      "${_binDir}/anwind-wine"

  # 全局配置（默认容器等；conf_write_kv 运行时回写）
  cat > "${_etcDir}/container.conf" << 'EOF'
# AnWind Wine 容器全局配置（anwind-container 维护）
# default_container=<容器名>
EOF

  # 登录 profile：把 rootfs bin 并入 PATH，终端直接敲 anwind-container
  cat > "${_profDir}/anwind-container.sh" << 'EOF'
# AnWind Wine 容器管理（由 bionic rootfs 构建系统安装）
case ":${PATH}:" in
  *":/data/data/com.anwind/files/rootfs/usr/bin:"*) ;;
  *) export PATH="/data/data/com.anwind/files/rootfs/usr/bin:${PATH}" ;;
esac
EOF

  # 容器存储与日志目录（rootfs home 下）
  mkdir -p "${destDir}/data/data/com.anwind/files/rootfs/home/.anwind/containers/logs" 2>/dev/null || true

  echo "anwind-container 安装完成"
}
