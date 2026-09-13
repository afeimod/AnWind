package com.winlator.cmod.core;

import android.content.Context;

/**
 * VKD3D 配置解析与运行时环境变量（AnWind 集成无 UI 版）。
 * 从 Winlator Cmod 的 VKD3DConfigDialog 中提取的纯静态逻辑。
 */
public abstract class VKD3DConfig {
    public static final String DEFAULT_CONFIG = DXVKConfig.DEFAULT_CONFIG + ",vkd3dLevel=12_1";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }
}
