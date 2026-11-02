revision="1.0.8"
url="https://sourceware.org/pub/bzip2/bzip2-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="others"
license="bzip2-1.0.6"

extra_fuction() {
  # 构建共享库
  make -f Makefile-libbz2_so
  # 构建静态库和工具
  make -j$(nproc)
  # 安装静态库、工具和头文件
  DESTDIR="${destDir}" make PREFIX="${prefix}" install
  # 手动安装共享库
  mkdir -p "${destDir}${prefix}/lib"
  cp -a libbz2.so* "${destDir}${prefix}/lib/"
}

# ===== backup: original autotools/make build =====
# extra_fuction() {
#   for targetArch in $_buildArchs; do
#     destDir="/tmp/build-${targetArch}"
#     cd "${_srcDir}"
#     load_env "$targetArch"
#     make clean 2>/dev/null || true
#     make CC="${CC}" CFLAGS="-O3 -pipe" LDFLAGS="${LDFLAGS}" libbz2.so -j$(nproc) || compile_err
#     mkdir -p "${destDir}/usr/lib" "${destDir}/usr/include"
#     cp -a libbz2.so* "${destDir}/usr/lib/"
#     cp -a bzlib.h "${destDir}/usr/include/"
#     package
#   done
# }
