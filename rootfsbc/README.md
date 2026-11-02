# Android Bionic Rootfs

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

# 7. 开源协议 (SPDX 标识, 用于自动安装协议文件与生成 THIRD_PARTY_NOTICES)
license="MIT"  # 如 zlib | MIT | LGPL-2.1-or-later | BSD-3-Clause | Apache-2.0

# 8. 特殊标记（按需设置）
# doNotMakePackage=1  # 无包项目：不生成/解压 tar，由项目内部判断是否重建

# 9. 构建参数（二选一）
args="..."
# fullCustomArgs="..."  # 完整替代 args，定义后 args 被忽略

# 10. 依赖项
deps="..."

# 11. Meson 专用（可选）
# customCrossFile="${wsDir}/projects/myproject/cross-${targetArch}-meson.txt"
# extraMesonCrossProps="has_function_iconv_open = false"

# 12. 自定义函数（如有）
pre_setup() { ... }
custom_configure() { ... }
custom_url() { ... }
extra_fuction() { ... }
install() { ... }
```

**注意：**
- `revision` 放在 `url` 之前，因为 tar 类型的 URL 使用 `${revision}` 变量
- 全局 `srcDir`（`default.conf`）默认为 `${wsDir}/src`，包级 `pkgSrcDir` 用于指向其子目录或本地源码目录
- 特殊标记仅在需要时出现，完整列表见下方"构建行为开关"
- 函数定义统一放在变量声明之后

## build.sh 变量参考

| 变量 | 必填 | 说明 |
|---|---|---|
| `revision` | 是 | 版本号，同时是 git tag（`urlType=git`）；决定包文件名 `pkgs/<pjName>-<revision>-<arch>.tar`，变更后自动触发该包重建 |
| `url` | 是 | 源码地址（git 仓库 / tarball 链接等） |
| `urlType` | 是 | 源码获取方式：`git`（`--depth=1 -b $revision` 浅克隆）、`tar`、`7z`、`wget`（仅下载到 /tmp/download-src）、`local`（使用已有 `src/<pjName>`）、`others`（必须定义 `custom_url`） |
| `arch` | 是 | 支持的目标架构（空格分隔）；当前构建架构不在列表中时整个包跳过 |
| `buildSys` | 是 | `cmake` \| `meson` \| `autotools` \| `make` \| `others`（完全自定义，必须定义 `extra_fuction`）\| `debug`（进入交互 shell 调试环境） |
| `license` | 是 | SPDX 协议标识，`gen_notices()` 汇总生成第三方清单 |
| `deps` | 否 | 空格分隔的依赖包名，拓扑排序后先构建依赖 |
| `args` | 否 | 传给 configure/cmake/meson 的附加参数（与 `fullCustomArgs` 二选一） |
| `fullCustomArgs` | 否 | 完整替代默认构建参数（包括 base 参数如 `mesonBaseArgs`/`cmakeBaseArgs`/`configureBaseArgs` 和 `args`）；定义后 `args` 被忽略，适用于需要完全接管参数的场景 |
| `pkgSrcDir` | 否 | 源码实际目录（源码根不在 `src/<pjName>` 顶层时指定）；同时影响协议文件查找目录 |
| `extraMesonCrossProps` | 否 | 注入到 meson cross file `[properties]` 段的额外属性（如 `has_function_iconv_open = false`） |
| `customCrossFile` | 否 | 自定义 cross file 路径（meson），支持 `${targetArch}` 变量展开；定义后替代 `cross-file/cross-${arch}-meson.sh` |

## 构建行为开关（特殊标记）

| 变量 | 作用范围 | 说明 |
|---|---|---|
| `doNotApplyPatch=1` | 全部 | 跳过 `projects/<pjName>/*.patch` 补丁应用 |
| `doNotUseAutogenSh=1` | autotools | 不执行 autogen.sh |
| `argsToAutogenSh=1` | autotools | 把构建参数传给 autogen.sh 而非 configure |
| `forceAutoreconf=1` | autotools | 强制 `autoreconf -fi` 重新生成 configure |
| `doNotUseNinja=1` | cmake | 用 make 而非 ninja 构建 |
| `doNotRunCMakeInstallOnCmake=1` | cmake | 安装用 `make install` 而非 `cmake --install` |
| `doNotMakePackage=1` | 全部 | 无包项目：不生成/解压 tar（详见下文） |

以上变量均已纳入 `empty.sh` 的加载前重置，不会在包间泄漏。

## 自定义函数（钩子）

主脚本对每个包的调用生命周期：

```
source empty.sh（重置变量/函数） → source projects/<pjName>/build.sh → make_pkg()
  ├─ 包 tar 已存在 → 跳过构建，直接解包到 prefix
  └─ 需要构建 → get_src → apply_patches → build_system → package()
```

> 补丁只在新获取的源码上应用：`--has-src` 模式下 `get_src` 与 `apply_patches` 一并跳过（现有源码视为已打好补丁），避免反复打补丁。

| 函数 | 触发时机 | 说明 |
|---|---|---|
| `pre_setup` | configure/setup 之前（cmake/meson/autotools） | 准备源码、向 `args` 追加参数、设置宿主工具路径等；meson 构建中始终先于 cross file 生成 |
| `custom_url` | `urlType=others` | 自定义源码获取逻辑，必须定义 |
| `custom_patch` | apply_patches 阶段 | 替代默认的 `patch -p1` 补丁应用 |
| `custom_configure` | autotools configure 阶段 | 完全替代默认 configure 流程 |
| `install` | make/meson/cmake install 阶段 | 替代默认安装命令，自动传入 `DESTDIR`/`PREFIX` |
| `extra_fuction` | `buildSys=others` | 完全自定义构建流程，必须定义 |

## 补丁机制

`apply_patches`（项目补丁）与 `apply_ndk_patches`（NDK sysroot 补丁）共用根目录 [patch-rules.conf](./patch-rules.conf) 中的规则，应用前由 `expand_patch_placeholders` 对补丁内容做占位符替换：

| 规则 | 替换为 |
|---|---|
| `@TERMUX_PREFIX@` | `${prefix}`（构建前缀变量，`default.conf` 定义、可被 `custom.conf` 覆盖） |
| `@TERMUX_PREFIX_CLASSICAL@` | 同上 |
| `@ROOTFS_PREFIX@` | 同上 |
| `@TERMUX_BASE_DIR@` | `${rootfsBase}` |
| `@TERMUX_HOME@` | `${rootfsHome}` |

- 规则文件格式为 `@占位符@ 变量名`（如 `@TERMUX_HOME@ rootfsHome`），不写死任何路径
- 替换在 bash 层完成；函数内临时关闭 bash 5.2+ 的 `patsub_replacement`，因此变量值含 `&`、`\` 等字符也不会被误解释
- 变量未定义、或替换后仍残留 `@TERMUX_*@`/`@ROOTFS_*@` 时展开失败并中止，防止把占位符字面量写进产物
- 新增/调整替换规则直接编辑 `patch-rules.conf` 即可，无需改动 `build-rootfs-arch.sh`
- NDK 补丁状态键含 `prefix`/`rootfsHome`/`rootfsBase`/补丁与规则文件指纹，任一变化会重新下载 NDK 并重打补丁

**关于 `__TERMUX__` 宏**：`__TERMUX__` / `__TERMUX_PREFIX__` 是编译期宏，由 NDK sysroot 补丁（`ndk_patches/*/sys-cdefs.h.patch`）定义，不要在 `patch-rules.conf` 中做 `__TERMUX__` → `__ANDROID__` 的文本替换——那会破坏补丁中的宏定义，并把类似 wine 补丁里 `#if defined(__ANDROID__) && !defined(__TERMUX__)` 的条件判断变成永远为假。

## doNotMakePackage：无包项目

适用场景：构建产物不进入 rootfs 的项目（如输出到 `host_tools/` 的宿主构建工具），生成 tar 包没有意义。

设为 `1` 后 `make_pkg()` 的行为变化：

- 跳过 tar 存在性判定与解包，`package()` 直接返回（不生成 tar、不安装协议文件）
- **每次构建都执行** `build_system`（`buildSys=others` 时即 `extra_fuction`），由项目脚本内部逻辑判断是否真正重建，而不是"有包就解压完事"
- 源码获取：源码目录缺失、或目录内 `.src-revision` 标记与 `revision` 不一致（即版本升级）时才重新 `get_src`，避免每次运行都重新克隆

典型示例 `projects/wine-tools/build.sh`（宿主 wine 构建工具，产物只进 `host_tools/`）：

```bash
doNotMakePackage=1

extra_fuction() {
  local wantVer="${revision#wine-}"   # wine-10.15 -> 10.15
  local haveVer=""
  [[ -x "$wineBuild" ]] && haveVer="$("$wineBuild" --version 2>/dev/null | awk '{print $3}')"
  if [[ "$haveVer" != "$wantVer" ]]; then
    # 版本不一致（或尚未构建）→ rm -rf 后重建宿主工具
  fi
}
```

## 宿主工具（host_tools）模式

交叉编译时需在构建机上运行的工具（host 工具）持久保存到 `host_tools/<pjName>/`，不进 rootfs、不打包，配合 `doNotMakePackage=1` 使用。现有三个先例：

| 项目 | 产物 | 版本判断方式 |
|---|---|---|
| `wine-tools` | widl/wrc/wmc/winebuild 等（完整构建树） | 运行产物 `winebuild --version` 与 `${revision#wine-}` 比对 |
| `wayland` | wayland-scanner | 运行 `wayland-scanner --version` 与 `$revision` 比对 |
| `icu` | 交叉构建用主机 ICU | `.icu-version` stamp 文件与 `$revision` 比对 |

写法约定：

- 重建条件 = 关键产物缺失 **或** 版本不一致；版本一致时打印跳过信息
- 重建前 `rm -rf` 整个 `host_tools/<pjName>`，避免新旧产物混用
- 消费方同样要校验：如 `wine-amd64` 在 `pre_setup` 中校验宿主工具版本与自身 `revision` 一致，不一致立即 `exit 1` 并提示 `--rebuild wine-tools` 重建，避免把不匹配的工具带进交叉 configure 后才报出难排查的错误

## 主脚本常用参数

```
./build-rootfs-arch.sh --build <pjName|all>       构建指定包或所有包
./build-rootfs-arch.sh --rebuild <pjName|all>     仅重建指定包（依赖不解构）
./build-rootfs-arch.sh --rebuild-deps <pjName>    重建指定包及其所有依赖
./build-rootfs-arch.sh --arch <archs>             指定目标架构 (arm64|aarch64 x86_64|amd64|all)
./build-rootfs-arch.sh --has-src                  src 下已有对应源码时跳过代码获取与打补丁
./build-rootfs-arch.sh --load-env [arch]          加载环境变量用于调试
./build-rootfs-arch.sh --clean-ccache             清理 ccache 缓存
./build-rootfs-arch.sh --update-host-deps         更新主机依赖
```

`--build` / `--rebuild` / `--rebuild-deps` 与 `--arch`、`--has-src` 可以任意先后顺序书写：
动作类选项只登记，等整个命令行解析完才统一执行。省掉 `--arch` 时按 `aarch64` 与 `x86_64`
两个架构各构建一遍，日志里会先打一条 WARN 说明。

例：只构建 arm64 版本的 glib 及其依赖

```
./build-rootfs-arch.sh --arch arm64 --build glib
```

## `${destDir}${prefix}/include` 安装路径说明

项目中头文件安装使用 `${destDir}${prefix}/include/glob.h` 而非 `${destDir}/include/glob.h`，原因：

1. **FHS 合规** — Linux 标准规定系统头文件应放在 `/usr/include/`，`${prefix}` 通常为 `/usr`，所以实际路径是 `${destDir}/usr/include/`
2. **sysroot 交叉编译** — `${destDir}` 是 sysroot 目录，头文件必须在 `${sysroot}/usr/include/` 才能被交叉编译器找到
3. **`${prefix}` 可配置** — 不同包可能设为 `/usr`、`/usr/local`、`/opt` 等，硬编码 `/include` 会破坏灵活性
4. **避免安装冲突** — 直接放到 `${destDir}/include` 可能覆盖其他包的文件

## 开源协议处理

### 协议文件自动安装

[获取SPDX协议列表](https://spdx.org/licenses/)

每个 `projects/*/build.sh` 需声明 `license` 字段（SPDX 标识，见上方变量规范）。打包时
`install_license()` 会从源码目录查找常见协议文件名（`LICENSE`、`COPYING`、`COPYING.GPL` 等）
并安装到 `${prefix}/share/licenses/${pjName}/`，随包进入 rootfs。

查找规则：
- 优先在 `${pkgSrcDir:-${srcDir}/${pjName}}` 中查找
- 若 `pkgSrcDir` 指向子目录（如 icu）且未找到，则回退到其父目录查找

#### 自定义协议文件

`agreementTargetFile` 用于为某包附带任意协议/版权文件（不局限于 `LICENSE`、`COPYING` 等标准文件名）。
打包时 `install_license()` 会将其一并复制到该包对应的协议目录（见下方"目标路径"）。

**支持三种写法：**

```bash
agreementTargetFile="src/foo/LICENSE src/foo/COPYING"        # 多个文件（空格分隔）
agreementTargetFile="src/foo/COPYING*"                       # 通配符（可匹配多个文件）
agreementTargetFile="${wsDir}/patches/zzz/EXTRA_LICENSE"     # 绝对路径
```

- 相对路径基于工作目录（`wsDir`）解析，如 `src/nettle/COPYING*` 即 `${wsDir}/src/nettle/COPYING*`
- 每个路径/通配符可独立命中，命中一个就复制一个到协议目录

**判断与容错逻辑：**

- 复制前判断 `-f`（存在且为常规文件）；目录或不存在的文件不会复制
- 某个路径/通配符**全部无匹配**时仅打印警告，不中断构建
- 只要复制成功任一文件，即视为该包已有协议文件（`_found=1`），从而**避免**在源码无协议时写入最小协议信息

**与 `license` 变量的关系：**

- `license` 仍建议声明（SPDX 标识，用于 `gen_notices()` 生成第三方组件清单）
- `agreementTargetFile` 仅负责实际协议文本的复制，两者独立、不冲突
- 若包声明了 `agreementTargetFile` 但未找到任何标准协议文件，可选择不声明 `license`，由自定义文件兜底

**目标路径：** `${prefix}/share/licenses/${pjName}`

> 该变量为包级配置，已纳入 `empty.sh` 在每次加载 `projects/*/build.sh` 前重置，避免包间泄漏。

### 第三方组件清单

`make-rootfs.sh` 导出 rootfs 时会调用 `utils/notices.sh` 的 `gen_notices()`，把本次
导出里每个包的 `license`、`url` 字段汇总成
`<导出目录>/usr/share/licenses/THIRD_PARTY_NOTICES`。

清单在导出阶段生成而不是构建阶段：`build-rootfs-arch.sh` 的 `package()` 只把
`${destDir}` 打进 tar，构建机上的 `${prefix}` 是临时目录，写在那里既进不了任何包，
导出后的 rootfs 里也不会有这个文件。

不传包名时 `gen_notices()` 会扫描 `projects/*/build.sh`，一般只在调试时手动调用。

### 协议约定

- 仓库本身（构建基础设施）采用 **GPLv3**，见根目录 `LICENSE`
- `projects/*/*.patch` 补丁与对应上游包采用**相同协议**（如 glib 补丁为 LGPL-2.1-or-later）
- `projects/libandroid-*` 等独立源码自带 `LICENSE` 文件，不随仓库主协议
- 各上游包的二进制再分发义务（版权声明保留、协议文本附带）通过上述自动安装机制满足

## project_legacy 遗留项目

`project_legacy/` 存放**不再参与构建**的包（归档保留，`build.sh` 不会被扫描或执行）。
这个遗产项目的真正用处是提供可用的例子，当然也不存在完全正确就是了，用于确认未来的构建方向，

## 鸣谢

[termux-packages](https://github.com/termux/termux-packages)