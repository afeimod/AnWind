package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

/**
 * DXVK 配置解析与运行时环境变量（AnWind 集成无 UI 版）。
 * 从 Winlator Cmod 的 DXVKConfigDialog 中提取的纯静态逻辑。
 */
public abstract class DXVKConfig {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0,async=0,asyncCache=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        // v11 修复（容器启动失败 NumberFormatException: For input string: ""）：
        // 旧版 UI 曾以 ';'-分隔或手输方式写入 dxwrapperConfig，脏数据里缺失
        // version 键 → setupWineSystemFiles 拼出 "dxvk-"（空版本）→
        // extractDXWrapperFiles → compareVersion(parseInt("")) 崩溃。
        // 此处统一把 ';' 清洗为 KeyValueSet 的 ',' 分隔，并在 version 缺失时
        // 兜底默认版本，保证调用方永远拿到可解析的版本号。
        data = data.replace(';', ',');
        KeyValueSet set = new KeyValueSet(data);
        if (set.get("version").isEmpty()) set.put("version", DefaultVersion.DXVK);
        return set;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, ImageFs.CONFIG_PATH+"/dxvk.conf");

        String content = "\"";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+';';
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+';';
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        content = content + '\"';

        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        envVars.put("DXVK_CONFIG", content);
    }
}
