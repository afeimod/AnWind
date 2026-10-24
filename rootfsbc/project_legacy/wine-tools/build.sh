revision="wine-10.15"
url="https://github.com/wine-mirror/wine.git"
urlType="git"
# 实际上只需要x86_64
arch="x86_64 aarch64"
buildSys="others"
license="LGPL-2.1"
# 构建产物只进 host_tools（宿主构建工具，不进 rootfs），无需生成软件包；
# 每次构建都会执行 extra_fuction，由内部的版本判断决定是否重建
doNotMakePackage=1

# tools 树（makedep/winebuild/widl/wrc/wmc/sfnt2fon + libs/wine、libs/wpp）不链接
# alsa/pulse/gstreamer/opengl 等运行时库，那些特性由 wine-amd64 自己的 configure 决定，
# 因此这里不镜像 wine-amd64 的参数，只保留 tools 实际需要的 freetype（sfnt2fon 用）

# wine-tools 必须在宿主机上构建，不使用交叉编译
# 构建产物（widl, winegcc, wrc, wmc 等）将被 wine-amd64 的 --with-wine-tools 使用
# 安装到 host_tools/wine-tools/ 目录，与 wayland-scanner 等其他宿主工具区分

extra_fuction() {
  local hostTools="${wsDir}/host_tools/wine-tools"
  local wineBuild="${hostTools}/tools/winebuild/winebuild"
  # 期望版本取自 revision（wine-10.15 -> 10.15）
  # winebuild --version 输出 "winebuild version 10.15"，第 3 个字段即版本号
  local wantVer="${revision#wine-}"
  local haveVer=""
  [[ -x "$wineBuild" ]] && haveVer="$("$wineBuild" --version 2>/dev/null | awk '{print $3}')"

  # 已构建工具的版本与 revision 不一致（含尚未构建）时重建
  if [[ "$haveVer" != "$wantVer" ]]; then
    [[ -n "$haveVer" ]] && echo "wine-tools 版本不一致: 已构建 ${haveVer}, 需要 ${wantVer}, 重新构建..."
    echo "构建宿主机 wine-tools..."
    load_env_host
    
    # 清理旧的构建产物
    rm -rf "${hostTools}"
    
    # 配置 wine-tools（本机构建，非交叉编译）
    # 不用 --prefix，因为 wine-amd64 的 configure 需要的是源码树布局（tools/winebuild 目录）
    # doNotMakePackage 模式下源码目录会跨构建保留，先清理上次的构建子目录
    rm -rf wine-tools
    mkdir wine-tools
    cd wine-tools
    ../configure \
      --enable-win64 \
      --with-freetype \
      --without-x \
      --without-fontconfig \
      --without-gnutls \
      --without-cups \
      --without-pulse \
      --without-alsa \
      --without-gstreamer \
      --without-opengl \
      --without-vulkan \
      --without-wayland \
      || configure_err
    # 构建所有 wine 工具（__tooldeps__ 会构建 tools/ 下的所有工具）
    make __tooldeps__ -j$(nproc) || compile_err
    make -C nls -j$(nproc) || compile_err
    
    cd ..

    mkdir -p "${wsDir}/host_tools"
    cp -r -p wine-tools ${wsDir}/host_tools/
    
    echo "wine-tools 构建完成 => ${hostTools}"
  else
    echo "wine-tools ${wantVer} 已是最新版本，跳过构建"
  fi
}
