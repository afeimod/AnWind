revision="1.19"
url="${gnuMirrorURL}/libiconv/libiconv-${revision}.tar.gz"
# ftp.gnu.org 偶发对 CI 出口 IP 限流，ftpmirror/kernel.org 镜像兜底（runner 实测可达）
backupUrls=(
  "https://ftpmirror.gnu.org/libiconv/libiconv-${revision}.tar.gz"
  "https://mirrors.kernel.org/gnu/libiconv/libiconv-${revision}.tar.gz"
  # v3.3.42：删掉 Debian pool 兜底 —— Debian **没有独立的 libiconv 源码包**
  # （libiconv 已并入 glibc），pool 实测该 URL 恒 404，等于白等一轮。
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="LGPL-2.1-or-later"
args="--enable-extra-encodings"