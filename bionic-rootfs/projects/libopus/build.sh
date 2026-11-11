revision="1.6.1"
url="https://downloads.xiph.org/releases/opus/opus-${revision}.tar.gz"
# v3.3.42 修正（与 libogg 同因，CI 实证）——
#   1) downloads.xiph.org 302 跳 osuosl，该跳板在 CI runner 上常被 TLS
#      中断 → 直接把 osuosl 主站写成兜底源，绕开跳转；
#   2) 原 Debian 兜底扩展名写错（.tar.gz）—— pool 实测 opus 1.6.1 只有
#      **opus_1.6.1.orig.tar.xz**（1.3.1/1.5.2 才是 .tar.gz），旧 URL
#      永远 404。
backupUrls=(
  "https://ftp.osuosl.org/pub/xiph/releases/opus/opus-${revision}.tar.gz"
  "http://deb.debian.org/debian/pool/main/o/opus/opus_${revision}.orig.tar.xz"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="
Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic,
                    Jean-Marc Valin, Timothy B. Terriberry,
                    CSIRO, Gregory Maxwell, Mark Borgerding,
                    Erik de Castro Lopo, Mozilla, Amazon
"
args="--disable-extra-programs"
