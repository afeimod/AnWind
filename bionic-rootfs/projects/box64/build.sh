# v0.4.4：对齐 hangover-11.16 官方配套（AndreRH/hangover-11.16 release
# notes 明确 "Updated Box64 to v0.4.4"）。hangover↔box64 的 PE/wow64 协议
# 版本敏感 —— v0.4.2 与 hangover-11.16 组合在设备上实证 wineboot SEH
# invalid frame 级联崩溃（容器 5555 日志；box64#2365 同款崩溃类别的
# 版本回归）。升级后 rootfs 自带 box64 与内置 hangover wine 配套。
revision="${ANWIND_BOX64_REF:-v0.4.4}"
url="https://github.com/ptitSeb/box64.git"
urlType="git"
arch="aarch64"
buildSys="cmake"
license="MIT"
args="-DCMAKE_BUILD_TYPE=RelWithDebInfo -DANDROID=ON -DBAD_SIGNAL=ON -DARM_DYNAREC=ON -DNO_CONF_INSTALL=ON -DNO_LIB_INSTALL=ON"

pre_setup() {
  args+=" -DCMAKE_ASM_FLAGS="
}

install() {
  mkdir -p "${destDir}${prefix}/local/bin/"
  cp -r -p ${srcDir}/box64/build/box64 "${destDir}${prefix}/local/bin/"
}