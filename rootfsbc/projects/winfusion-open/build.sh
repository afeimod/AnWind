revision="1.0.0"

# 源码就在本目录（src/open.c）。
urlType="local"
pkgSrcDir="${wsDir}/projects/winfusion-open"

arch="aarch64 x86_64"
buildSys="others"
# 与 winfusion-shm 保持一致（同一来源的 WinFusion 自有代码，二者互相链接）。
license="LGPL-2.1-or-later"

# 依赖 winfusion-shm：open 静态链接其中的 winfusion_open_ipc（闭包 shm_ring / shm_client），
# 头文件 <shm/drivers/open_driver.h> 与库都由它装进 prefix。
deps=(winfusion-shm)

# rootfs 的 usr/bin/open —— 容器里点开的链接的落点程序。
# winebrowser 在没有 Windows 浏览器时把 http/https/mailto 交给它
# （候选表最前面的绝对路径见 wine-bionic/programs/winebrowser/main.c 的 open_helper_path()）。
# 它只做一件事：把整串目标经共享内存发给安卓侧的 OpenDriver，由那边决定是不是真的用安卓浏览器打开。
extra_fuction() {
  # CFLAGS 里已带 -I${prefix}/include，LDFLAGS 里已带 -L${prefix}/lib。
  # 与 winfusion-shm 一致地去掉 -flto=thin：链的是普通 ELF 归档，免掉 LTO 的跨目标依赖。
  local _cFlags="${CFLAGS//-flto=thin/}"
  local _lFlags="${LDFLAGS//-flto=thin/}"

  mkdir -p "${destDir}${prefix}/bin"

  # 依赖由 winfusion-shm 的包解到真正的 ${prefix}（不是 ${destDir}${prefix}），
  # 所以头文件在这里找。守卫一是断言依赖包是新的：框架只按
  # pkgs/<名>-<revision>-<arch>.tar 是否存在决定 "要不要重编"，改了 winfusion-shm 的内容
  # 却没动它的 revision，就会把旧包解出来，编译期只报一句 'file not found'，看不出所以然。
  [ -f "${prefix}/include/shm/drivers/open_driver.h" ] ||
    { err "prefix 里没有 open_driver.h —— winfusion-shm 的包是旧的，请动它的 revision 后重建"; exit 1; }

  # 静态库依赖有先后：被依赖者写在后面（open_ipc -> ring -> client）。
  ${CC} ${_cFlags} -o "${destDir}${prefix}/bin/open" "${pkgSrcDir}/src/open.c" \
    -lwinfusion_open_ipc -lwinfusion_shm_ring -lwinfusion_shm_client \
    ${_lFlags} || { err "编译失败 => open"; exit 1; }
}
