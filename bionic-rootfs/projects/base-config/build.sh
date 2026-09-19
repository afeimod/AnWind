revision="2026-v1"
urlType="local"
arch="aarch64 x86_64"
deps="pthread-stub"
pkgSrcDir="${wsDir}/projects/base-config"
buildSys="others"
license="GPL-3.0"

# linux一些基本的配置文件

extra_fuction() {
  mkdir -p "${destDir}${prefix}/etc"
echo "写入hosts"
cat > "${destDir}${prefix}/etc/hosts" << EOF
127.0.0.1 localhost
::1 ip6-localhost

EOF
echo "写入resolv"
cat > "${destDir}${prefix}/etc/resolv.conf" << EOF
nameserver 223.5.5.5
nameserver 8.8.8.8
nameserver 8.8.4.4

EOF

  mkdir -p "${destDir}${prefix}/etc/ca-certificates"
  wget -nv -P "${destDir}${prefix}/etc/ca-certificates/" https://curl.haxx.se/ca/cacert.pem || { echo "文件下载失败!" && exit 1;}

cat > "${destDir}${prefix}/etc/machine-id" << EOF
d0f88608a7756c173fe9057c6a620caf
EOF

  # ---- AnWind X11 (termux-x11 / libXlorie) 运行时环境 ----
  # AnWind 内置 X 服务监听 App 侧前缀 tmp/.X11-unix/Xn（默认显示号 :1），
  # rootfs 内的 X 客户端（wine 等）通过这些环境变量连接它。
  mkdir -p "${destDir}${prefix}/etc/profile.d"
  cat > "${destDir}${prefix}/etc/profile.d/anwind-x11.sh" << EOF
# AnWind X11 运行时环境（由 bionic rootfs 构建系统生成）
export DISPLAY=\${DISPLAY:-:1}
export XDG_RUNTIME_DIR=\${XDG_RUNTIME_DIR:-/data/data/com.anwind/files/usr/tmp}
# XKB 键盘数据（xkeyboard-config 安装位置）
export XKB_CONFIG_ROOT=\${XKB_CONFIG_ROOT:-${prefix}/share/X11/xkb}
EOF

  # X11 unix socket 目录占位（X 服务在 App 侧前缀创建同名目录）
  mkdir -p "${destDir}${prefix}/../tmp/.X11-unix" 2>/dev/null || true
  mkdir -p "${destDir}${prefix}/var/tmp"
}
