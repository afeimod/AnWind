# wineguanli.py 重制 — /opt 文件夹清单（v3.2.15）

按你提供的 wineguanli.py 重制了桌面「Wine 容器」应用（左右分栏 + 选项卡，
宽屏生效；窄屏保留卡片列表）。**读 /opt 的部分留好了槽位，你只需要准备
下列文件夹/文件**。AnWind 原生链已覆盖的功能不需要 /opt。

## 一、AnWind 已原生实现（无需 /opt）

| wineguanli.py 功能 | AnWind 实现 |
| --- | --- |
| 容器列表 / 新建 / 删除 / 重命名 / 克隆 | 容器管理窗口（container.conf + 前缀目录） |
| Wine 版本选择 | /usr/opt 槽位扫描（WineSlotSelector，与终端 anwind-container 一致） |
| 分辨率 / 语言 / 帧数限制 | container.conf：screen= / lang= / frame=（DXVK_FRAME_RATE 注入） |
| CPU 核心分配 | container.conf：cores=（PRIMARY_CORES/SECONDARY_CORES 环境变量注入） |
| dsound / dshow / DPI / Windows 版本 | container.conf：dsound= dshow= dpi= winver=，启动链自动 regedit 写入前缀注册表 |
| DXVK 注入 | APK assets/dxvk/dxvk-*.tar.gz（原生，前缀构建时自动解压到 C:\windows） |
| DllOverrides 注册表 | 启动链自动注入 147 项（v3.2.14 已有） |
| MangoHud | container.conf：mangohud=（MANGOHUD=1 + MANGOHUD_CONFIG 注入） |
| Unity 单线程渲染 | flags=unity → BOX64_UNITYPLAYER=1 |
| 高级开关（opengl/f5task/ib/debug/独立窗口/关服务） | container.conf：flags=（保留槽位） |

## 二、需要你准备的 /opt 文件夹（对接自带脚本时）

以下路径来自 wineguanli.py 原文，放进 rootfs（`/data/data/com.anwind/files/rootfs/opt/...`）
即可被你自带的脚本消费；AnWind 侧的「驱动版本槽位」文本框写入的就是这些目录里的
**tar 包名**（存 container.conf 的 turnip= / zink= / wined3d=）。

```
/opt/
├── conf/                      # 全局配置目录
│   ├── env.conf               # 环境变量（export K=V 行）
│   ├── cores.conf             # export PRIMARY_CORES="0-3" / SECONDARY_CORES="4-7"
│   ├── library.conf           # export dsound= / dshow= / dpi= / windows_version=
│   ├── custom.conf            # Dynarec 变量目录（export VAR=0,1,2 可选值列表）
│   ├── dynarec_active.conf    # Dynarec 当前生效值（export VAR=值）
│   ├── d3d.conf               # Direct3D 配置
│   └── wine_path.conf         # export WINE_PATH= / WINEPREFIX= / CONTAINER_NAME=
├── prefix/                    # 新前缀注册表模板
│   ├── user.reg
│   └── system.reg
├── box/                       # box64 tar 包（*.tar.gz/xz/bz2 → 解到 /usr/local/bin）
├── box86/                     # box86 tar 包（同上）
├── tz/                        # Turnip 驱动 tar 包（→ /opt/mesa1/lib）
├── zink/                      # Zink 驱动 tar 包（→ /opt/mesa1/lib）
├── mesa1/lib/                 # mesa 库目录（turnip/zink 解压落点）
├── dxvk1/                     # DXVK tar 包（dxvk-<ver>.tar.gz → 前缀 drive_c/windows）
├── vkord3d/                   # WineD3D tar 包（→ 前缀 drive_c/windows）
├── cncddraw/                  # CnCDDraw 配置
├── apps/                      # 内置应用（如 tfm.exe —— wineguanli 默认启动目标）
└── mod/d78to<version>/        # 模组目录

/home/linbox/<容器名>/
└── .wine/                     # wineguanli 生态的前缀目录（注意：AnWind 原生容器
                              # 前缀在 rootfs/home/.anwind/containers/<名>/prefix，
                              # 两套并存互不影响；你自带的 startwine 类脚本按
                              # wine_path.conf 的 WINEPREFIX 指哪用哪）
```

## 三、container.conf 新键速查（v3.2.15）

```
frame=0|60|120        帧数限制（DXVK_FRAME_RATE）
cores=0-3             主核心集（PRIMARY_CORES/SECONDARY_CORES）
dsound=builtin,native DLL 加载序（同时写入 user.reg）
dshow=builtin,native  quartz DLL 加载序（同上）
dpi=96                LogPixels（user.reg）
winver=win10          win10/win81/win8/win7/vista/winxp/win2000/win98/win95
mangohud=             MANGOHUD_CONFIG 串（空=关）
flags=unity,opengl    高级开关 csv：unity/opengl/f5task/ib/debug/independent/closeservice
turnip=               /opt/tz 包名（槽位）
zink=                 /opt/zink 包名（槽位）
wined3d=              /opt/vkord3d 包名（槽位）
```

## 四、差异说明

- wineguanli.py 的「架构选择（x86_64/arm64ec）」在 AnWind 对应「CPU 翻译后端」
  （auto/arm64ec/box64/hangover/fexcore/native），按 wine ELF 形态自动判定。
- wineguanli.py 的 dynarec 编辑器（DynarecEditor）依赖 /opt/conf/custom.conf
  变量目录；AnWind 原生用 Box64 预设（性能/兼容）+ env= 手工覆盖
  BOX64_DYNAREC_*。需要完整编辑器时把 custom.conf 放进 /opt/conf 并接上
  你的读取脚本即可（变量清单与 wineguanli.py 相同）。
