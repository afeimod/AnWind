# AnWind Bionic Rootfs（X11 版）

基于 Android NDK 交叉编译、针对 **Bionic (Android libc)** 的 rootfs 构建系统，
供 [AnWind](https://github.com/afeimod/AnWind)（`com.anwind`）使用。

> 本项目改编自 android-bionic-rootfs，主要改动：
> 1. 全部路径/标识改为 AnWind（`com.anwind`，rootfs 前缀
>    `/data/data/com.anwind/files/rootfs/usr`）
> 2. **显示协议由 Wayland/Weston 改为 X11**：
>    - 删除 `projects/wayland`、`projects/wayland-protocols`
>    - mesa 改为 `platforms=x11` + `glx=dri`（DRI3/GLX/EGL over X11）
>    - wine（hangover-wine）改为 `--with-x`（xrender/xrandr/xi/xcursor/
>      xinerama/xcomposite/xxf86vm/…）
>    - Vulkan Loader 开启 XCB/XLIB WSI；gstreamer 开启 x11 插件
>    - libxkbcommon 开启 `enable-x11`
>    - 新增完整 X11 客户端库栈：xorgproto、libxau、libxdmcp、xcb-proto、
>      libxcb、xtrans、libX11、libXext、libXrender、libXfixes、libXdamage、
>      libXrandr、libXi、libXinerama、libXcursor、libXcomposite、
>      libXxf86vm、libxshmfence、libXv
> 3. X11 unix socket 路径补丁：AnWind 内置 X 服务（libXlorie / termux-x11）
>    在 **App 侧前缀** `$APP_PREFIX/tmp/.X11-unix/Xn`
>    （`/data/data/com.anwind/files/usr/tmp/.X11-unix/X1`，默认显示号 `:1`）
>    监听，xtrans / libxcb 已补丁对齐该路径（见 `patch-rules.sed`
>    的 `@APP_PREFIX@` 规则与 `projects/libxcb/build.sh`）。

## rootfs 运行时布局

```
/data/data/com.anwind/files/rootfs/     <- rootfs 根
├── usr/                                <- ${prefix}（本构建系统安装前缀）
├── home/                               <- ${rootfsHome}
└── tmp/                                <- rootfs 临时目录
```

App 侧 X11 socket：`/data/data/com.anwind/files/usr/tmp/.X11-unix/X1`

## 构建

```bash
./host-deps.sh                          # 安装宿主依赖（debian/ubuntu/arch）
./build-rootfs-arch.sh --arch aarch64 --build all   # 构建所有包
./build-rootfs-arch.sh --arch x86_64  --build all
./make-rootfs.sh --export /path/to/rootfs --arch aarch64   # 组装 rootfs
```

CI 一键构建见 AnWind 仓库 `.github/workflows/build-bionic-rootfs.yml`
（Actions → "Build AnWind Bionic Rootfs (X11)" → Run workflow）。

## build.sh 变量顺序规范

所有 `projects/*/build.sh` 应遵循以下变量顺序：

```bash
# 1. 版本信息（url 依赖此变量）
revision="x.y.z"

# 2. 源码地址
url="https://..."

# 3. 源码类型
urlType="git"  # git | tar | 7z | wget | local | others

# 4. 源码子目录（可选，源码不在 src/<pjName> 顶层时指定）
pkgSrcDir="${srcDir}/..."

# 5. 目标架构
arch="aarch64 x86_64"

# 6. 构建系统
buildSys="cmake"  # cmake | meson | autotools | make | others | debug

# 7. 开源协议 (SPDX 标识)
license="MIT"

# 8. 特殊标记（按需设置）
# doNotMakePackage=1

# 9. 构建参数（二选一）
args="..."
# fullCustomArgs="..."

# 10. 依赖项
deps="..."

# 11. 自定义函数（如有）
pre_setup() { ... }
custom_configure() { ... }
custom_url() { ... }
extra_fuction() { ... }
install() { ... }
```

## 主脚本常用参数

```
./build-rootfs-arch.sh --build <pjName|all>       构建指定包或所有包
./build-rootfs-arch.sh --rebuild <pjName|all>     仅重建指定包（依赖不解构）
./build-rootfs-arch.sh --rebuild-deps <pjName>    重建指定包及其所有依赖
./build-rootfs-arch.sh --arch <archs>             指定目标架构 (aarch64 x86_64|all)
./build-rootfs-arch.sh --has-src                  src 下已有对应源码时跳过代码获取与打补丁
./build-rootfs-arch.sh --load-env [arch]          加载环境变量用于调试
./build-rootfs-arch.sh --clean-ccache             清理 ccache 缓存
./build-rootfs-arch.sh --update-host-deps         更新主机依赖
```

## 补丁机制

`apply_patches`（项目补丁）与 `apply_ndk_patches`（NDK sysroot 补丁）共用根目录
[patch-rules.sed](./patch-rules.sed) 中的规则，应用前对补丁内容做占位符替换：

| 规则 | 替换为 |
|---|---|
| `@TERMUX_PREFIX@` / `@ROOTFS_PREFIX@` | `${prefix}`（rootfs 安装前缀） |
| `@TERMUX_HOME@` | `${rootfsHome}` |
| `@APP_PREFIX@` | `${appPrefix}`（App 侧 termux 前缀，X11 socket 所在） |

**关于 `__TERMUX__` 宏**：`__TERMUX__` / `__TERMUX_PREFIX__` 是编译期宏，由 NDK sysroot
补丁（`ndk_patches/*/sys-cdefs.h.patch`）定义，不要在 `patch-rules.sed` 中做文本替换。

## 开源协议处理

每个 `projects/*/build.sh` 声明 `license` 字段（SPDX 标识）。打包时
`install_license()` 从源码目录查找协议文件并安装到
`${prefix}/share/licenses/${pjName}/`；`gen_notices()` 汇总生成
`${prefix}/share/licenses/THIRD_PARTY_NOTICES`。

## 鸣谢

[termux-packages](https://github.com/termux/termux-packages)
