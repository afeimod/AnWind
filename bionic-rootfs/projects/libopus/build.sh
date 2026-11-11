revision="1.6.1"
url="https://downloads.xiph.org/releases/opus/opus-${revision}.tar.gz"
# downloads.xiph.org 历史上多次长时间不可用，Debian pool 兜底
backupUrls=(
  "http://deb.debian.org/debian/pool/main/o/opus/opus_${revision}.orig.tar.gz"
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