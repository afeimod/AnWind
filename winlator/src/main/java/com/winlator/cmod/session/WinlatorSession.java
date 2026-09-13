package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.box86_64.rc.RCFile;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DXVKConfig;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GraphicsDriverConfigParser;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.VKD3DConfig;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.x11.X11DisplayComponent;
import com.winlator.cmod.x11.X11DisplayHost;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.BionicProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Winlator 容器会话编排器（AnWind v2.23 集成）。
 *
 * 职责：以 Winlator Cmod 7.1.4x 引擎启动一个容器内的 exe，显示端由
 * AnWind 自带的 X11（termux-x11/lorie + LorieView 浮动窗口）承担。
 *
 * 完整启动链（对应原版 XServerDisplayActivity 的生命周期，剔除其
 * XServer/渲染栈后重组）：
 *   1. ImageFsInstaller.installIfNeeded —— imagefs/proton/组件落位；
 *   2. ContainerManager.activateContainer —— home/xuser → 当前容器；
 *   3. setupWineSystemFiles —— DX 包装器 / DDraw 包装器 / Windows 组件
 *      / 音频驱动注册表 / 系统 tweaks 落进容器 wineprefix；
 *   4. 图形驱动环境（turnip ICD + zink + MESA_VK_WSI_DEBUG=sw）——
 *      Vulkan(DXVK/VKD3D) 为 GPU 主路径，与 lorie 显示端适配；
 *   5. XEnvironment 组件组：SysVSHM + X11DisplayComponent（lorie 桥）
 *      + NetworkInfo + ALSA/PulseAudio + BionicProgramLauncher；
 *   6. host.openDisplayWindow() —— AnWind 桌面弹出 X11 浮动窗口，
 *      LorieView 连接 lorie 渲染，游戏画面即在其中。
 */
public class WinlatorSession {
    public enum State { IDLE, INSTALLING, STARTING, RUNNING, STOPPING, ERROR }

    /** 宿主回调（由 AnWind :app 模块实现，桥接桌面基础设施）。 */
    public interface Host {
        /** 确保 AnWind X server（lorie）已运行（见 X11DisplayHost 注释）。 */
        boolean ensureX11Session();

        /** 容器分辨率 → AnWind 分辨率握手（screenSize 形如 "1280x720"）。 */
        void applyContainerResolution(String screenSize);

        /** 打开/聚焦 AnWind 桌面的 X11 浮动窗口。 */
        void openDisplayWindow();

        /** X11InputHub 持有的 WinHandler 单例（UDP 7947 全局唯一）。 */
        WinHandler getWinHandler();

        /** 会话消息（进度/错误提示，由宿主决定展示方式）。 */
        void onSessionMessage(String message);
    }

    private static final String TAG = "WinlatorSession";
    private static volatile WinlatorSession instance;

    public static WinlatorSession get() {
        if (instance == null) {
            synchronized (WinlatorSession.class) {
                if (instance == null) instance = new WinlatorSession();
            }
        }
        return instance;
    }

    public interface StateListener { void onStateChange(State state, String message); }

    private volatile State state = State.IDLE;
    private volatile String message = "";
    private StateListener stateListener;

    private Context context;
    private Host host;
    private Container container;
    private WineInfo wineInfo;
    private ContentsManager contentsManager;
    private XEnvironment environment;
    private BionicProgramLauncherComponent bionicLauncher;
    private final EnvVars envVars = new EnvVars();
    private KeyValueSet dxwrapperConfig;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public State getState() { return state; }
    public String getMessage() { return message; }
    public Container getContainer() { return container; }

    public void setStateListener(StateListener listener) { this.stateListener = listener; }

    private void setState(State newState, String msg) {
        state = newState;
        message = msg != null ? msg : "";
        Log.i(TAG, "state → " + newState + (msg != null && !msg.isEmpty() ? " : " + msg : ""));
        StateListener l = stateListener;
        if (l != null) l.onStateChange(newState, message);
    }

    public boolean isBusy() { return state == State.INSTALLING || state == State.STARTING || state == State.RUNNING || state == State.STOPPING; }

    // ==================================================================
    // 安装 / 启动
    // ==================================================================

    /** 确保引擎资产（imagefs）就绪；完成后回调（任意线程）。 */
    public void ensureImageFs(Context context, Host host, ImageFsInstaller.Progress progress, Callback<Boolean> done) {
        this.context = context.getApplicationContext();
        this.host = host;
        setState(State.INSTALLING, "检查引擎资产…");
        ImageFsInstaller.installIfNeeded(this.context, progress, (ok) -> {
            setState(ok ? State.IDLE : State.ERROR, ok ? "" : "imagefs 安装失败（引擎资产缺失或损坏）");
            if (done != null) done.call(ok);
        });
    }

    /**
     * 启动容器内的 exe（后台执行）。[exePath] 为 Android 本地路径
     * （SAF / 文件管理器提供），内部自动映射为容器 DOS 盘符路径。
     */
    public void runExe(Context context, Host host, Container container, String exePath, Callback<Integer> termination) {
        if (isBusy()) {
            if (host != null) host.onSessionMessage("已有容器会话在运行，请先停止当前会话");
            return;
        }
        this.context = context.getApplicationContext();
        this.host = host;
        this.container = container;
        this.stopping.set(false);
        setState(State.STARTING, "正在启动容器…");

        executor.execute(() -> {
            try {
                doRun(exePath, termination);
            }
            catch (Throwable t) {
                Log.e(TAG, "容器启动失败", t);
                setState(State.ERROR, "容器启动失败: " + t.getMessage());
            }
        });
    }

    private void doRun(String exePath, Callback<Integer> termination) {
        ImageFs imageFs = ImageFs.find(context);
        String rootPath = imageFs.getRootDir().getPath();

        // ---- 1. 引擎资产（同步等待安装完成） ----
        if (!imageFs.isValid() || imageFs.getVersion() < ImageFsInstaller.LATEST_VERSION) {
            final java.util.concurrent.Semaphore installSem = new java.util.concurrent.Semaphore(0);
            final AtomicBoolean installOk = new AtomicBoolean(false);
            ImageFsInstaller.installIfNeeded(context, (percent, msg) ->
                setState(State.STARTING, msg != null ? msg : "准备引擎 (" + percent + "%)"), (ok) -> {
                installOk.set(ok);
                installSem.release();
            });
            installSem.acquireUninterruptibly();
            if (!installOk.get()) {
                setState(State.ERROR, "imagefs 安装失败（引擎资产缺失或损坏，检查 APK assets）");
                return;
            }
        }
        ImageFs imageFs2 = ImageFs.find(context);

        // ---- 2. 容器激活 + Wine 版本 ----
        ContainerManager containerManager = new ContainerManager(context);
        containerManager.activateContainer(container);

        contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        imageFs2.setWinePath(wineInfo.path);

        envVars.clear();
        envVars.put("LC_ALL", container.getLC_ALL() != null && !container.getLC_ALL().isEmpty() ? container.getLC_ALL() : "en_US.UTF-8");
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", imageFs2.wineprefix);
        envVars.put("WINEDEBUG", "-all");

        FileUtils.clear(imageFs2.getTmpDir());

        WinHandler winHandler = host != null ? host.getWinHandler() : null;
        if (winHandler != null && container.getInputType() >= 0)
            winHandler.setInputType((byte) container.getInputType());
        // 注：host.getWinHandler() 内部已懒启动 UDP 7947（X11InputHub 单例，
        // 幂等）—— winhandler.exe / XInput 手柄通道由此就绪。

        // ---- 3. wineprefix 系统文件 ----
        boolean firstTimeBoot = container.getExtra("appVersion").isEmpty();
        setupWineSystemFiles(firstTimeBoot);

        // ---- 4. 图形驱动环境（AnWind X11/lorie 适配） ----
        setupGraphicsDriverEnv(firstTimeBoot);

        // ---- 5. 音频驱动注册表 ----
        changeWineAudioDriver();

        // ---- 6. 盘符（含 picked exe 所在存储的自动绑定） ----
        WineUtils.createDosdevicesSymlinks(container);
        ensureDriveForPath(exePath);

        // ---- 7. RC 文件（Box64 per-app 配置） ----
        RCManager rcManager = new RCManager(context);
        rcManager.loadRCFiles();
        RCFile rcfile = rcManager.getRcfile(container.getRCFileId());
        File rcFile = new File(container.getRootDir(), ".box64rc");
        FileUtils.writeString(rcFile, rcfile == null ? "" : rcfile.generateBox86_64rc());
        envVars.put("BOX64_RCFILE", rcFile.getAbsolutePath());

        // ---- 8. FEXCore app 配置 ----
        FEXCoreManager.createAppConfigFiles(context);

        // ---- 9. 服务终止回调里的启动选择 ----
        byte startupSelection = container.getStartupSelection();
        if (winHandler != null && startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE) {
            winHandler.killProcess("services.exe");
        }

        // ---- 10. 组装 XEnvironment（显示端 = X11DisplayComponent） ----
        String guestExecutable = "wine " + getWineStartCommand(exePath);

        bionicLauncher = new BionicProgramLauncherComponent(contentsManager, null, null);
        bionicLauncher.setContainer(container);
        bionicLauncher.setWineInfo(wineInfo);
        bionicLauncher.setWoW64Mode(container.isWoW64Mode());
        bionicLauncher.setGuestExecutable(guestExecutable);
        bionicLauncher.setEnvVars(envVars);
        bionicLauncher.setTerminationCallback((status) -> {
            Log.i(TAG, "guest 进程退出 status=" + status);
            environment.stopEnvironmentComponents();
            setState(status == 0 || stopping.get() ? State.IDLE : State.ERROR,
                status == 0 || stopping.get() ? "" : "容器进程已退出 (status=" + status + ")");
            if (termination != null) termination.call(status);
        });

        environment = new XEnvironment(context, imageFs2);
        environment.addComponent(new SysVSharedMemoryComponent(
            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        // 显示端：AnWind X11（lorie）——替代原版 XServerComponent
        environment.addComponent(new X11DisplayComponent(new X11DisplayHost() {
            @Override public boolean ensureX11Session() {
                return host != null && host.ensureX11Session();
            }
            @Override public void applyContainerResolution() {
                if (host != null) host.applyContainerResolution(container.getScreenSize());
            }
            @Override public void openDisplayWindow() {
                if (host != null) host.openDisplayWindow();
            }
        }));
        environment.addComponent(new NetworkInfoUpdateComponent());

        String audioDriver = container.getAudioDriver();
        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(new ALSAServerComponent(
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)));
        }
        else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(new PulseAudioComponent(
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)));
        }

        // Box64 预设
        String box64Preset = container.getBox64Preset();
        bionicLauncher.setBox64Preset(box64Preset != null && !box64Preset.isEmpty() ? box64Preset : Box86_64Preset.COMPATIBILITY);
        ArrayList<String> bindingPaths = new ArrayList<>();
        for (String[] drive : container.drivesIterator()) bindingPaths.add(drive[1]);
        bionicLauncher.setBindingPaths(bindingPaths.toArray(new String[0]));

        environment.addComponent(bionicLauncher);

        // ---- 11. 启动 ----
        environment.startEnvironmentComponents();
        setState(State.RUNNING, container.getName() + " · " + FileUtils.getName(exePath));

        // 开窗 + 分辨率握手（lorie 广播到达后 X11WindowController 亦会自动开窗）
        if (host != null) host.openDisplayWindow();
    }

    // ==================================================================
    // 停止
    // ==================================================================

    public void stop() {
        if (!isBusy() || !stopping.compareAndSet(false, true)) return;
        setState(State.STOPPING, "正在停止容器…");
        executor.execute(() -> {
            try {
                if (environment != null) environment.stopEnvironmentComponents();
                if (bionicLauncher != null) bionicLauncher.stop();
                // 兜底清理残余 wine 进程（仅 bionic imagefs 侧，经 TERM 优雅收尾）
                ProcessHelper.terminateAllWineProcesses();
            }
            catch (Throwable t) {
                Log.w(TAG, "停止容器失败", t);
            }
            finally {
                setState(State.IDLE, "");
            }
        });
    }

    // ==================================================================
    // wineprefix 系统文件（移植自 XServerDisplayActivity）
    // ==================================================================

    private void setupWineSystemFiles(boolean firstTimeBoot) {
        ImageFs imageFs = ImageFs.find(context);
        String appVersion = String.valueOf(getVersionCode(context));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }

        String dxwrapper = container.getDXWrapper();
        if (dxwrapper.equals("dxvk")) {
            dxwrapperConfig = DXVKConfig.parseConfig(container.getDXWrapperConfig());
            dxwrapper = "dxvk-" + dxwrapperConfig.get("version");
        }
        else if (dxwrapper.equals("vkd3d")) {
            dxwrapperConfig = VKD3DConfig.parseConfig(container.getDXWrapperConfig());
            dxwrapper = "vkd3d-" + dxwrapperConfig.get("vkd3dVersion");
        }
        else dxwrapperConfig = null;

        if (!dxwrapper.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(dxwrapper);
            container.putExtra("dxwrapper", dxwrapper);
            containerDataChanged = true;
        }

        String ddrawrapper = container.getDDrawWrapper();
        if (!ddrawrapper.equals(container.getExtra("ddrawrapper"))) {
            extractDDrawrapperFiles(ddrawrapper);
            container.putExtra("ddrawrapper", ddrawrapper);
            containerDataChanged = true;
        }

        if (ddrawrapper.equals("cnc-ddraw"))
            envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

        String wincomponents = container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles(firstTimeBoot);
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();

        // OpenGL 组件原生开关（保持原版语义：非 arm64ec 才处理 opengl 之外的组件）
        KeyValueSet wincomponentsSet = new KeyValueSet(container.getWinComponents());
        for (String[] wincomponent : wincomponentsSet) {
            String identifier = wincomponent[0];
            boolean useNative = wincomponent[1].equals("1");
            if (!wineInfo.isArm64EC() && identifier.contains("opengl") && useNative) continue;
            if (useNative || firstTimeBoot)
                WineUtils.overrideWinComponentDlls(context, container, identifier, useNative);
        }

        WineUtils.applySystemTweaks(context, wineInfo);
        if (container.getExtra("desktopTheme") != null) {
            // desktopTheme 保持原值即可（壁纸由 WineThemeManager 按需安装）
        }
    }

    private void applyGeneralPatches(Container container) {
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "container_pattern_common.tzst", rootDir);
        File pulseDir = new File(context.getFilesDir(), "pulseaudio");
        if (!pulseDir.isDirectory()) {
            pulseDir.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "pulseaudio.tzst", pulseDir);
        }
        WineUtils.applySystemTweaks(context, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll"};
        ImageFs imageFs = ImageFs.find(context);
        File windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("vkd3d")) {
            ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/dxvk-2.4.1.tzst", windowsDir);
            if (profile != null) {
                contentsManager.applyContent(profile);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/" + dxwrapper + ".tzst", windowsDir);
            }
        }
        else if (dxwrapper.contains("dxvk")) {
            ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
            if (profile != null) {
                contentsManager.applyContent(profile);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/" + dxwrapper + ".tzst", windowsDir);
                if (compareVersion(StringUtils.parseNumber(dxwrapper), "2.4") < 0) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir);
                }
            }
        }
        else if (dxwrapper.contains("wined3d")) {
            restoreOriginalDllFiles(dlls);
        }
    }

    private void extractDDrawrapperFiles(String ddrawrapper) {
        final String[] dlls = {"ddraw.dll", "d3dimm.dll"};
        final String[] glideDlls = {"glide.dll", "glide2x.dll", "glide3x.dll", "3DfxSpl.dll", "3DfxSpl2.dll", "3DfxSpl3.dll"};
        ImageFs imageFs = ImageFs.find(context);
        File windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");

        for (String glideDLL : glideDlls) FileUtils.delete(new File(windowsDir, "syswow64/" + glideDLL));

        if (ddrawrapper.equals("wined3d")) {
            restoreOriginalDllFiles(dlls);
        }
        else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir);
        }
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "ddrawrapper/nglide.tzst", windowsDir);
    }

    private void extractWinComponentFiles(boolean firstTimeBoot) {
        ImageFs imageFs = ImageFs.find(context);
        File windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = container.getWinComponents();

            java.util.Iterator<String[]> oldWinComponentsIter =
                new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (!wineInfo.isArm64EC() && identifier.contains("opengl") && useNative) continue;

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/" + identifier + ".tzst", windowsDir);
                }
                else {
                    org.json.JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int j = 0; j < dlnames.length(); j++) {
                        String dlname = dlnames.getString(j);
                        dlls.add(!dlname.endsWith(".exe") ? dlname + ".dll" : dlname);
                    }
                }
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
        }
        catch (Exception e) {
            Log.w(TAG, "extractWinComponentFiles", e);
        }
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        ImageFs imageFs = ImageFs.find(context);
        File windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");
        File system32dlls, syswow64dlls;

        if (wineInfo.isArm64EC())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        else
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/x86_64-windows");

        syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");

        for (String dll : dlls) {
            FileUtils.copy(new File(system32dlls, dll), new File(windowsDir, "system32/" + dll));
            FileUtils.copy(new File(syswow64dlls, dll), new File(windowsDir, "syswow64/" + dll));
        }
    }

    private void changeWineAudioDriver() {
        if (!audioDriverChanged()) return;
        ImageFs imageFs = ImageFs.find(context);
        File userRegFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (container.getAudioDriver().equals("alsa")) {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
            }
            else if (container.getAudioDriver().equals("pulseaudio")) {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
            }
        }
        catch (Exception e) {
            Log.w(TAG, "changeWineAudioDriver", e);
        }
        container.putExtra("audioDriver", container.getAudioDriver());
        container.saveData();
    }

    private boolean audioDriverChanged() {
        return !container.getAudioDriver().equals(container.getExtra("audioDriver"));
    }

    // ==================================================================
    // 图形驱动环境（lorie 适配：turnip + DXVK 主路径）
    // ==================================================================

    private void setupGraphicsDriverEnv(boolean firstTimeBoot) {
        String graphicsDriver = container.getGraphicsDriver();
        HashMap<String, String> graphicsDriverConfig =
            GraphicsDriverConfigParser.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());

        // DX/VK 包装器运行时环境
        String dxwrapper = container.getDXWrapper();
        if (dxwrapper.equals("dxvk") && dxwrapperConfig != null) {
            DXVKConfig.setEnvVars(context, dxwrapperConfig, envVars);
        }
        else if (dxwrapper.equals("vkd3d") && dxwrapperConfig != null) {
            VKD3DConfig.setEnvVars(context, dxwrapperConfig, envVars);
        }

        // lorie 无 DRI3：强制 Mesa WSI 软件呈现路径（MIT-SHM / XPutImage 拷贝）
        envVars.put("MESA_VK_WSI_DEBUG", "sw");

        // Vulkan ICD：容器图形驱动 = turnip（Adreno GPU 直连）时用 freedreno ICD；
        // 其它（wrapper/virgl 依赖 Winlator 自带 X server 的路径在 lorie 下不可用）也回落 freedreno。
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/freedreno_icd.aarch64.json");
        // （wrapper_icd 依赖 Winlator 自带 X server 的呈现路径，lorie 下统一回落 turnip）
        envVars.put("GALLIUM_DRIVER", "zink");
        envVars.put("LIBGL_KOPPER_DISABLE", "true");

        // 扩展黑名单（原版 wrapper 驱动属性，保留透传）
        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        if (blacklistedExtensions != null) envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && !maxDeviceMemory.isEmpty())
            envVars.put("WRAPPER_MAX_DEVICE_MEMORY", maxDeviceMemory);

        String frameSync = graphicsDriverConfig.get("frameSync");
        if (frameSync != null && !frameSync.isEmpty())
            envVars.put("WRAPPER_FRAME_SYNC", frameSync);

        // 首次启动重铺 wrapper/extra_libs（与原版一致，保持 imagefs 完整性）
        if (firstTimeBoot) {
            File rootDir = imageFs.getRootDir();
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/wrapper.tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/extra_libs.tzst", rootDir);
        }
    }

    // ==================================================================
    // exe 路径 → DOS 盘符路径
    // ==================================================================

    /** 确保 exe 所在目录可经某个盘符访问（/storage 自动绑 y:）。 */
    private void ensureDriveForPath(String exePath) {
        if (exePath == null || exePath.isEmpty()) return;
        if (toDosPath(exePath) != null) return;
        File exe = new File(exePath);
        String abs = exe.getAbsolutePath();
        if (abs.startsWith("/storage")) {
            File dosdevices = new File(container.getRootDir(), ".wine/dosdevices");
            dosdevices.mkdirs();
            File link = new File(dosdevices, "y:");
            link.delete();
            FileUtils.symlink("/storage", link.getPath());
        }
    }

    /** Android 路径 → 容器 DOS 路径（盘符最长前缀匹配；找不到返回 null）。 */
    public String toDosPath(String exePath) {
        String abs = new File(exePath).getAbsolutePath();
        String bestDrive = null; String bestTarget = null; int bestLen = -1;
        for (String[] drive : container.drivesIterator()) {
            String target = new File(drive[1]).getAbsolutePath();
            if (!target.endsWith("/")) target += "/";
            if (abs.startsWith(target) && target.length() > bestLen) {
                bestDrive = drive[0]; bestTarget = target; bestLen = target.length();
            }
        }
        if (bestDrive == null) return null;
        String rest = abs.substring(bestTarget.length()).replace('/', '\\');
        return bestDrive.toUpperCase() + ":\\" + rest;
    }

    private String getWineStartCommand(String exePath) {
        String args = "";
        String execArgs = envVars.get("EXTRA_EXEC_ARGS");
        envVars.remove("EXTRA_EXEC_ARGS");

        if (exePath != null && !exePath.isEmpty()) {
            String exeDir = FileUtils.getDirname(exePath);
            String filename = FileUtils.getName(exePath);
            String dosDir = toDosPath(exeDir);
            if (dosDir == null) dosDir = "Z:" + exeDir.replace('/', '\\');
            args += "/dir " + StringUtils.escapeDOSPath(dosDir) + " \"" + filename + "\"";
        }
        else {
            args += "\"winecfg\"";
        }
        if (!execArgs.isEmpty()) args += " " + execArgs;
        return args;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static int getVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        }
        catch (Exception e) {
            return 0;
        }
    }

    private static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        int numA, numB;
        for (int i = 0; i < minLen; i++) {
            numA = Integer.parseInt(levelsA[i]);
            numB = Integer.parseInt(levelsB[i]);
            if (numA != numB) return numA - numB;
        }
        return levelsA.length - levelsB.length;
    }
}
