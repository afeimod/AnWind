package com.winlator.cmod.core;

import java.util.HashMap;
import java.util.Map;

/**
 * AnWind（v2.23 winlator 集成）：图形驱动配置解析器。
 *
 * 从 Winlator Cmod 的 GraphicsDriverConfigDialog（UI 对话框）中提取的
 * 纯静态解析逻辑 —— 本集成不移植 Winlator 的 View/Dialog UI 栈
 * （容器设置界面由 AnWind 桌面的 Compose UI 提供），故把无 UI 依赖的
 * 配置序列化/反序列化方法移入本类，供 AdrenotoolsManager 与
 * WinlatorSession 使用。
 *
 * 配置格式（与 Winlator 保持一致，便于导入导出互通）：
 *   version=...;blacklistedExtensions=...;maxDeviceMemory=...;adrenotoolsTurnip=...;frameSync=...
 */
public abstract class GraphicsDriverConfigParser {
    public static final String DEFAULT_CONFIG =
        "version=System;blacklistedExtensions=;maxDeviceMemory=;adrenotoolsTurnip=1;frameSync=0";

    public static HashMap<String, String> parseGraphicsDriverConfig(String graphicsDriverConfig) {
        HashMap<String, String> mappedConfig = new HashMap<>();
        if (graphicsDriverConfig == null || graphicsDriverConfig.isEmpty()) return mappedConfig;
        String[] configElements = graphicsDriverConfig.split(";");
        for (String element : configElements) {
            String key;
            String value;
            String[] splittedElement = element.split("=");
            key = splittedElement[0];
            if (splittedElement.length > 1)
                value = element.split("=")[1];
            else
                value = "";
            mappedConfig.put(key, value);
        }
        return mappedConfig;
    }

    public static String toGraphicsDriverConfig(HashMap<String, String> config) {
        String graphicsDriverConfig = "";
        for (Map.Entry<String, String> entry : config.entrySet()) {
            graphicsDriverConfig += entry.getKey() + "=" + entry.getValue() + ";";
        }
        if (graphicsDriverConfig.isEmpty()) return "";
        return graphicsDriverConfig.substring(0, graphicsDriverConfig.length() - 1);
    }

    public static String getVersion(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("version");
    }

    public static String getExtensionsBlacklist(String graphicsDriverConfig) {
        HashMap<String, String> config = parseGraphicsDriverConfig(graphicsDriverConfig);
        return config.get("blacklistedExtensions");
    }
}
