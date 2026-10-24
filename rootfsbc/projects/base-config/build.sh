revision="2026-v1"
urlType="local"
arch="aarch64 x86_64"
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
  wget -P "${destDir}${prefix}/etc/ca-certificates/" https://curl.haxx.se/ca/cacert.pem || { echo "文件下载失败!" && exit 1;}

cat > "${destDir}${prefix}/etc/machine-id" << EOF
d0f88608a7756c173fe9057c6a620caf
EOF

  mkdir -p "${destDir}${prefix}/var/lib/dbus"
cat > "${destDir}${prefix}/var/lib/dbus/machine-id" << EOF
d0f88608a7756c173fe9057c6a620caf
EOF
}

