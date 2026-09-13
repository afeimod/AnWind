package com.winlator.cmod.xenvironment;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;

import java.io.File;
import java.util.concurrent.Executors;

/**
 * ImageFs 安装器（AnWind 集成独立版）。
 *
 * 从 Winlator Cmod 的 ImageFsInstaller 重写：去除 MainActivity/
 * DownloadProgressDialog UI 依赖，改为回调式（进度经 [Progress] 上报，
 * 由 AnWind 桌面的 Compose 界面呈现），逻辑保持一致：
 * - imagefs.txz（bionic Wine + Box64 + proton 运行时的根文件系统，
 *   构建期由 CI 从 Winlator 官方仓库拉取 LFS 资产）解压到
 *   files/imagefs；
 * - proton-9.0-*.txz（Wine 本体）解压到 imagefs/opt/<version>；
 * - libandroid-sysvshm.so（guest 侧 shm LD_PRELOAD，随本 APK 的
 *   native 库分发）拷入 imagefs/usr/lib；
 * - pulseaudio.tzst 解压到 files/pulseaudio（PulseAudio 组件运行时用）。
 */
public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 21;

    public interface Progress {
        void onProgress(int percent, String message);
    }

    /** 与原版 resetContainerImgVersions 等价：imagefs 重装后标记容器需要更新 wineprefix。 */
    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion)) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }
            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    /** 从资产安装 Wine 版本到 imagefs/opt/<version>。 */
    public static void installWineFromAssets(Context context) {
        String[] versions = {"proton-9.0-x86_64", "proton-9.0-arm64ec"};
        File rootDir = ImageFs.find(context).getRootDir();
        for (String version : versions) {
            File outFile = new File(rootDir, "/opt/" + version);
            outFile.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, version + ".txz", outFile);
        }
    }

    /** 把 APK native 库目录中的 libandroid-sysvshm.so 拷入 imagefs/usr/lib。 */
    private static void installSysVShmGuestLib(Context context, File rootDir) {
        try {
            File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File src = new File(nativeDir, "libandroid-sysvshm.so");
            if (!src.exists()) {
                android.util.Log.w("ImageFsInstaller", "libandroid-sysvshm.so 不在 nativeLibraryDir（CI 构建缺失？）");
                return;
            }
            File libDir = new File(rootDir, "/usr/lib");
            libDir.mkdirs();
            File dst = new File(libDir, "libandroid-sysvshm.so");
            FileUtils.copy(src, dst);
            FileUtils.chmod(dst, 0755);
        }
        catch (Exception e) {
            android.util.Log.w("ImageFsInstaller", "安装 libandroid-sysvshm.so 失败", e);
        }
    }

    private static void installFromAssets(Context context, Progress progress, Callback<Boolean> done) {
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            if (progress != null) progress.onProgress(5, "解压系统文件 (imagefs.txz)…");

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, "imagefs.txz", rootDir, (file, size) -> file);

            if (success) {
                if (progress != null) progress.onProgress(60, "解压 Wine (proton-9.0)…");
                installWineFromAssets(context);
                if (progress != null) progress.onProgress(85, "配置 guest 组件…");
                installSysVShmGuestLib(context, rootDir);
                // pulseaudio（PulseAudio 组件运行时经 files/pulseaudio 启动）
                File pulseDir = new File(context.getFilesDir(), "pulseaudio");
                pulseDir.mkdirs();
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "pulseaudio.tzst", pulseDir);
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(context);
                if (progress != null) progress.onProgress(100, "完成");
            }
            else if (progress != null) {
                progress.onProgress(100, "imagefs.txz 解压失败（检查 APK 内引擎资产是否齐全）");
            }

            if (done != null) done.call(success);
        });
    }

    /** 需要时安装（首次运行 / 版本升级）。 */
    public static void installIfNeeded(Context context, Progress progress, Callback<Boolean> done) {
        ImageFs imageFs = ImageFs.find(context);
        if (imageFs.isValid() && imageFs.getVersion() >= LATEST_VERSION) {
            if (done != null) done.call(true);
            return;
        }
        installFromAssets(context, progress, done);
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && file.getName().equals("home")) continue;
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }
}
