# AnWind CI 修复包（mesa / turnip / rootfs）

本包只包含相对你仓库当前版本有差异的文件，解压后按原目录结构直接覆盖合并到
AnWind 仓库根目录即可（.github/workflows/、bionic-rootfs/ 两处）。

## 根因与修复对照

| 现象 | 根因（已在 mesa-26.2.1 源码中核实） | 修复 |
|------|--------------------------------------|------|
| mesa 两个架构 job 均"到 mesa 即停" | 旧配方用 `-Dgallium-drivers=swrast,...`，而 mesa 25.1+ 已移除 swrast 驱动（26.2.1 的 meson.options choices 中无此项），meson 配置阶段立即报错 | projects/mesa/build.sh 改为 `softpipe`（产物名仍为 swrast_dri.so），并补 `-llog` |
| turnip（freedreno vulkan）构建失败 | 1) NDK 无平台私有头 cutils/native_handle.h、vndk/hardware_buffer.h，turnip/vulkan 工具层在 bionic 目标必须包含；2) NDK 的 libnativewindow 存根任何 API 级别都不导出 AHardwareBuffer_getNativeHandle（真机平台内部符号） | pre_setup 安装 mesa 自带 android_stub 头子集到 prefix；新增补丁 0014 弱引用 + 空检查优雅降级 |
| bionic 无 pthread_cancel，wsi_common_display.c 编译失败 | bionic 平台裁剪 | 新增补丁 0013（非 bionic 路径语义零改动） |
| build-mesa-bionic.yml 产物断言 | 旧断言 swrast_dri.so / softpipe_dri.so 与实际产物不符 | 断言改为 swrast_dri.so + kms_swrast_dri.so |
| rootfs 全量构建到 mesa 即停 | 同上（rootfs 按拓扑序构建，mesa 位于依赖末段，其失败导致整体中止） | 同上 |

## 附带同步（与全量包保持一致，避免后续踩坑）

- host-deps.sh：ubuntu-24.04 无 `pip` 命令导致 meson 可能未装上的隐患修复（python3 -m pip 回退 + meson 可用性断言）
- pthread-stub 新配方（libX11 依赖引用它）
- base-config / ffmpeg / fontconfig / hangover-wine / libX11 系列 / libcairo / libxau / libxdmcp / tzdata 的 build.sh 为最新校准版

## 验证情况

- aarch64（softpipe + freedreno MSM/KGSL + zink + turnip）已在本地 NDK 交叉构建**完整通过**，
  产物含 libvulkan_freedreno.so（turnip）、zink_dri.so、kgsl_dri.so、swrast_dri.so
- x86_64（softpipe）同配方此前已本地通过
