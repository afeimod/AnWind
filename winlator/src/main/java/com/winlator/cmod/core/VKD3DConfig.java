package com.winlator.cmod.core;

import android.content.Context;

/**
 * VKD3D 配置解析与运行时环境变量（AnWind 集成无 UI 版）。
 * 从 Winlator Cmod 的 VKD3DConfigDialog 中提取的纯静态逻辑。
 */
public abstract class VKD3DConfig {
    // v11：DEFAULT_CONFIG 显式携带 vkd3dVersion —— 原先空配置回落 DXVK 的
    // 默认串（version=1.10.1 无 vkd3dVersion 键），兜底迁移会把 DXVK 版本
    // 误当 VKD3D 版本（assets 无 vkd3d-1.10.1.tzst，提取静默失败）
    public static final String DEFAULT_CONFIG = DXVKConfig.DEFAULT_CONFIG
        + ",vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=12_1";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        // v11 修复（与 DXVKConfig.parseConfig 同源）：';' 脏数据清洗 + 版本键
        // 兜底 —— 引擎侧 setupWineSystemFiles 读 "vkd3dVersion" 拼
        // "vkd3d-<版本>"，键缺失时曾拼出 "vkd3d-"（空版本）。
        // 兼容旧数据：无 vkd3dVersion 但有旧 "version" 键时迁移该值
        // （VKD3D 版本命名均为 "2.x-y" 含 '-'，DXVK 风格纯数字点版本如
        // "1.10.1" 不迁移，避免错挂不存在的 VKD3D 资产）
        data = data.replace(';', ',');
        KeyValueSet set = new KeyValueSet(data);
        if (set.get("vkd3dVersion").isEmpty()) {
            String legacy = set.get("version");
            set.put("vkd3dVersion", (legacy.isEmpty() || !legacy.contains("-"))
                ? DefaultVersion.VKD3D : legacy);
        }
        if (set.get("vkd3dLevel").isEmpty()) set.put("vkd3dLevel", "12_1");
        return set;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        // v11：vkd3dLevel 兜底（parseConfig 已保证非空，防御旧调用方自建 KeyValueSet）
        String level = config.get("vkd3dLevel");
        envVars.put("VKD3D_FEATURE_LEVEL", level.isEmpty() ? "12_1" : level);
    }
}
