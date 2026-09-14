package com.winlator.cmod.session;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.box86_64.rc.RCFile;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
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
import com.winlator.cmod.core.TarCompressorUtils;
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

        // v2.25 修复：漏合并容器环境变量（上游 XServerDisplayActivity 在同位置
        // 执行 envVars.putAll(container.getEnvVars())）。DEFAULT_ENV_VARS 里的
        // ZINK_DESCRIPTORS/ZINK_DEBUG/mesa_glthread/TU_DEBUG=noconform,sysmem
        // 等对 turnip+DXVK 渲染路径至关重要，缺失会导致部分游戏初始化异常。
        envVars.putAll(container.getEnvVars());
        if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

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

        // v13：解析 exe 的 DOS 路径（unix 侧回查验证 + 专用盘符绑定）。
        // 背景：wine 侧报"File not found."的实际定位点是 winhandler 的
        // ShellExecuteExA —— 它按 /dir 盘符目录解析裸文件名；此前 v12 虽把
        // 映射算对了，但 dosdevices 里任何一个失效软链（如历史遗留的
        // e:→com.winlator.cmod/storage 死链、y: 创建静默失败）都会让 wine
        // 侧盘符解析落空。resolveExeDosPath 对每条候选映射做"unix 侧回查"，
        // 并把 exe 目录直接绑成专用盘符，从结构上消灭这一失效类。
        String[] exeDos = resolveExeDosPath(exePath);
        if (exeDos != null)
            Log.i(TAG, "exe DOS 映射: " + exeDos[0] + "（工作目录 " + exeDos[1] + "）");

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
        // v2.25 修复（exe 启动失败根因）：启动命令缺失两级前缀。上游完整命令为：
        //   wine explorer /desktop=shell,<宽x高> winhandler.exe /dir <dos目录> "文件.exe"
        // - explorer /desktop=shell：<宽x高> 建 wine 虚拟桌面（部分游戏必须，
        //   参见 X11ResolutionLink fix19 注释）；
        // - winhandler.exe：真正拉起目标 exe 的引擎自带的启动器（位于
        //   container_pattern_common.tzst 的 drive_c/windows/ 下），同时承担
        //   UDP 7947 窗口/进程信息回传（触摸→鼠标、手柄 XInput 通道的对端）。
        // 此前直接 "wine /dir … " 会把 /dir 当作程序名去加载，wine 立即
        // 报"cannot find '/dir'"退出 → 容器启动 exe 必败。
        // screenSize=="native" 时虚拟桌面取 1280x720 兜底，由 X11FitClient 贴合。
        String desktopResolution = container.getScreenSize();
        if (desktopResolution == null || !desktopResolution.matches("\\d+x\\d+")) desktopResolution = "1280x720";

        // v10 修复：启动前自检 + 兜底 —— 此前 wine 本体或 winhandler.exe 缺失
        // （引擎资产不完整/容器模板解压失败）时只会静默失败或桌面空壳，用户
        // 看到"exe 启动失败"却无线索。现在：
        // ① wine 二进制缺失 → 明确 ERROR 消息引导重装引擎资产；
        // ② winhandler.exe 缺失 → 回落 explorer 直启模式（游戏照常运行，
        //    仅牺牲 winhandler 的手柄/窗口管理通道）。
        File wineBinCheck = new File(wineInfo.path, "bin/wine");
        if (!wineBinCheck.isFile()) {
            Log.e(TAG, "wine 二进制缺失: " + wineBinCheck.getPath());
            setState(State.ERROR, "Wine 启动文件缺失（" + wineInfo.identifier() + "），请在引擎管理里重新安装引擎资产");
            return;
        }
        File winhandlerExe = new File(rootPath, ImageFs.WINEPREFIX + "/drive_c/windows/winhandler.exe");
        boolean hasWinHandler = winhandlerExe.isFile();
        if (!hasWinHandler)
            Log.w(TAG, "winhandler.exe 缺失（容器模板不完整），回落 explorer 直启模式: " + winhandlerExe.getPath());
        String guestExecutable = "wine explorer /desktop=shell," + desktopResolution + " " +
            (hasWinHandler
                ? "winhandler.exe " + getWineStartCommand(exePath, exeDos)
                : getDirectStartCommand(exePath, exeDos));
        Log.i(TAG, "guest 启动命令: " + guestExecutable);

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
            // v11 双保险：parseConfig 已兜底 version，这里再校验一次 ——
            // 此前脏数据拼出 "dxvk-"（空版本）→ extractDXWrapperFiles →
            // compareVersion(parseInt("")) NumberFormatException，容器启动失败
            String dxvkVer = dxwrapperConfig.get("version");
            if (dxvkVer == null || dxvkVer.isEmpty()) dxvkVer = DefaultVersion.DXVK;
            dxwrapper = "dxvk-" + dxvkVer;
        }
        else if (dxwrapper.equals("vkd3d")) {
            dxwrapperConfig = VKD3DConfig.parseConfig(container.getDXWrapperConfig());
            String vkd3dVer = dxwrapperConfig.get("vkd3dVersion");
            if (vkd3dVer == null || vkd3dVer.isEmpty()) vkd3dVer = DefaultVersion.VKD3D;
            dxwrapper = "vkd3d-" + vkd3dVer;
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
                if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/" + dxwrapper + ".tzst", windowsDir))
                    Log.w(TAG, "vkd3d 版本资产缺失: dxwrapper/" + dxwrapper + ".tzst（检查容器 dxwrapperConfig 的 vkd3dVersion）");
            }
        }
        else if (dxwrapper.contains("dxvk")) {
            ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
            if (profile != null) {
                contentsManager.applyContent(profile);
            } else {
                if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/" + dxwrapper + ".tzst", windowsDir))
                    Log.w(TAG, "dxvk 版本资产缺失: dxwrapper/" + dxwrapper + ".tzst（检查容器 dxwrapperConfig 的 version）");
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

        // v8 修复：驱动版本（graphicsDriverConfig 的 version 字段）此前从未被
        // 消费 —— 即使装了 Adreno 驱动包（turnip），容器也永远只用系统驱动。
        // 上游 XServerDisplayActivity 在同位置调用 setDriverById 注入
        // ADRENOTOOLS_* 环境变量挂载驱动；"System" = 系统驱动，不注入。
        String driverVersion = graphicsDriverConfig.get("version");
        if (driverVersion != null && !driverVersion.isEmpty() && !driverVersion.equals("System")) {
            try {
                new AdrenotoolsManager(context).setDriverById(envVars, imageFs, driverVersion);
            }
            catch (Throwable t) {
                Log.w(TAG, "Adreno 驱动挂载失败（version=" + driverVersion + "），回落系统驱动", t);
            }
        }

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
        // v12：canonical 化（/sdcard/... → /storage/emulated/0/...），
        // 否则软链路径开头判断失误，y: 永远绑不上
        File exe = new File(exePath);
        String abs;
        try { abs = exe.getCanonicalPath(); }
        catch (Exception e) { abs = exe.getAbsolutePath(); }
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
        if (exePath == null || exePath.isEmpty()) return null;
        // v12 修复（exe 全部"未发现"的根因）：此前仅遍历容器 drives 配置字符串，
        // ensureDriveForPath 刚绑定的 y:→/storage 不在其中 —— exe 位于
        // /storage/emulated/0/... 时 exeDir 匹配失败 → 被拼成 Z:\storage\...
        // （Z: 实际映射 imagefs 根，该路径不存在）→ winhandler 找不到 exe，
        // wine 界面出现后弹"未发现"错误框，所有外部存储 exe 均无法启动。
        // 现改为扫描 .wine/dosdevices 实际盘符链接（包含动态绑定的 y: 与标准
        // c:/z:），并对目标与源路径同时 canonical 化（/sdcard 等软链路径也能匹配）。
        File src = new File(exePath);
        String abs;
        try { abs = src.getCanonicalPath(); }
        catch (Exception e) { abs = src.getAbsolutePath(); }
        if (abs == null || abs.isEmpty()) return null;

        String bestDrive = null; String bestTarget = null; int bestLen = -1;
        File[] links = new File(container.getRootDir(), ".wine/dosdevices").listFiles();
        if (links != null) {
            for (File link : links) {
                String name = link.getName();
                if (name.length() != 2 || name.charAt(1) != ':') continue;
                String target;
                try { target = link.getCanonicalPath(); }
                catch (Exception e) { target = link.getAbsolutePath(); }
                if (!target.endsWith("/")) target += "/";
                if (abs.startsWith(target) && target.length() > bestLen) {
                    bestDrive = name; bestTarget = target; bestLen = target.length();
                }
            }
        }
        // 兜底：dosdevices 不可读（异常容器）时回落 drives 配置串匹配
        if (bestDrive == null) {
            for (String[] drive : container.drivesIterator()) {
                String target = new File(drive[1]).getAbsolutePath();
                if (!target.endsWith("/")) target += "/";
                if (abs.startsWith(target) && target.length() > bestLen) {
                    bestDrive = drive[0]; bestTarget = target; bestLen = target.length();
                }
            }
        }
        if (bestDrive == null || bestTarget == null) return null;
        String rest = abs.substring(bestTarget.length()).replace('/', '\\');
        return Character.toUpperCase(bestDrive.charAt(0)) + ":\\" + rest;
    }

    // ==================================================================
    // v13：exe DOS 路径解析（回查验证 + 专用盘符绑定）
    // ==================================================================

    /**
     * 解析 exe 的 DOS 路径，返回 {完整DOS路径, 工作目录}；无法解析返回 null。
     *
     * 策略（逐级兜底，每一步都以"unix 侧真实存在"为准）：
     * ① v12 的 dosdevices 扫描映射 → 回查验证（软链失效立即淘汰）；
     * ② 把 exe 所在目录直接绑成专用盘符（x:/w:/v:/…，创建即验证），
     *    得到 X:\&lt;文件名&gt; —— 不依赖任何既有盘符的正确性；
     * ③ exe 位于 imagefs 内部 → Z:（Z: 固定映射 imagefs 根）；
     * ④ 全部失败 → null（调用方保留旧行为并记日志）。
     *
     * 背景（v12 仍未根治"File not found."的原因）：映射计算正确 ≠ wine 侧
     * 可用。dosdevices 中任何一条悬空软链（DEFAULT_DRIVES 里硬编码的
     * com.winlator.cmod/storage 死链、y: 创建静默失败、/sdcard 与 /storage
     * 规范化差异等）都会让 wine 侧 SetCurrentDirectory/路径查找落空，而
     * Java 侧无从感知。本方法对所有候选映射强制回查，结构性杜绝。
     */
    private String[] resolveExeDosPath(String exePath) {
        if (exePath == null || exePath.isEmpty()) return null;

        // ① dosdevices 扫描 + 回查验证
        String dos = toDosPath(exePath);
        if (dos != null) {
            String unixBack = verifyDriveMapping(dos);
            if (unixBack != null && new File(unixBack).isFile()) {
                Log.i(TAG, "exe DOS 映射（dosdevices 扫描，回查通过）: " + dos);
                return new String[]{dos, parentDosDir(dos)};
            }
            Log.w(TAG, "dosdevices 扫描映射回查未通过（" + dos + " → " + unixBack
                + "），改用专用盘符绑定");
        }

        // ② 专用盘符：直接把 exe 目录绑成盘符根
        String exeDir = FileUtils.getDirname(exePath);
        File dirFile = new File(exeDir);
        String dirCanonical;
        try { dirCanonical = dirFile.getCanonicalPath(); }
        catch (Exception e) { dirCanonical = dirFile.getAbsolutePath(); }
        String letter = bindExeDrive(dirCanonical);
        if (letter != null) {
            String name = FileUtils.getName(exePath);
            String root = Character.toUpperCase(letter.charAt(0)) + ":\\.";
            String dosFile = Character.toUpperCase(letter.charAt(0)) + ":\\" + name;
            if (new File(dirCanonical, name).isFile()) {
                return new String[]{dosFile, root};
            }
            Log.w(TAG, "专用盘符绑定后文件回查失败: " + dosFile);
        }

        // ③ imagefs 内部 → Z:（Z: 由 createDosdevicesSymlinks 固定指向 imagefs 根）
        // v13.1 编译修复：此处沿用 ensureDriveForPath 的写法，先由 exePath
        // 构造 File（此前引用了未声明的变量 exe，导致 CI 编译失败）。
        File exe = new File(exePath);
        String abs;
        try { abs = exe.getCanonicalPath(); }
        catch (Exception e) { abs = exe.getAbsolutePath(); }
        String imagefsRoot = ImageFs.find(context).getRootDir().getPath();
        if (abs.startsWith(imagefsRoot) && exe.isFile()) {
            String dosZ = "Z:" + abs.substring(imagefsRoot.length()).replace('/', '\\');
            Log.w(TAG, "exe 回落 Z: 盘符: " + dosZ);
            return new String[]{dosZ, parentDosDir(dosZ)};
        }

        Log.e(TAG, "exe 无法映射到任何容器盘符（dosdevices 扫描/专用绑定/Z: 均失败）: " + exePath);
        return null;
    }

    /**
     * 把目录绑成专用盘符：候选字母 x,w,v,u,t,s,r,q，跳过配置盘与 c:/z:，
     * 候选位直接重建软链（FileUtils.symlink 内部先删旧链），创建后立即
     * 验证可解析。成功返回盘符字母，失败返回 null。
     */
    private String bindExeDrive(String dirCanonical) {
        if (dirCanonical == null || dirCanonical.isEmpty()) return null;
        File dosdevices = new File(container.getRootDir(), ".wine/dosdevices");
        if (!dosdevices.isDirectory()) dosdevices.mkdirs();

        java.util.Set<String> configured = new java.util.HashSet<>();
        configured.add("c"); configured.add("z");
        for (String[] d : container.drivesIterator())
            configured.add(d[0].toLowerCase(java.util.Locale.ENGLISH));

        String[] candidates = {"x", "w", "v", "u", "t", "s", "r", "q"};
        for (String letter : candidates) {
            if (configured.contains(letter)) continue;
            File link = new File(dosdevices, letter + ":");
            FileUtils.symlink(dirCanonical, link.getPath());
            if (link.exists()) {
                Log.i(TAG, "exe 专用盘符绑定: " + letter.toUpperCase(java.util.Locale.ENGLISH)
                    + ": → " + dirCanonical);
                return letter;
            }
            Log.w(TAG, "专用盘符软链不可用（创建失败？）: " + link.getPath());
        }
        return null;
    }

    /**
     * 回查验证：把 DOS 路径经 dosdevices 软链目标换算回 unix 路径。
     * 返回换算出的 unix 绝对路径（调用方再判断存在性）；软链失效/无法
     * 换算返回 null。这里只做换算与软链可解析性检查，不判 isFile ——
     * 便于目录/文件两种场景复用。
     */
    private String verifyDriveMapping(String dosPath) {
        if (dosPath == null || dosPath.length() < 3 || dosPath.charAt(1) != ':') return null;
        File link = new File(container.getRootDir(),
            ".wine/dosdevices/" + Character.toLowerCase(dosPath.charAt(0)) + ":");
        if (!link.exists()) return null;
        String target;
        try { target = link.getCanonicalPath(); }
        catch (Exception e) { return null; }
        String rest = dosPath.substring(2).replace('\\', '/');
        File unix = new File(target, rest);
        try { return unix.getCanonicalPath(); }
        catch (Exception e) { return unix.getAbsolutePath(); }
    }

    /** "X:\dir\file.exe" → "X:\dir"；根目录场景返回 "X:\."（避免悬尾反斜杠的引号陷阱）。 */
    private static String parentDosDir(String dosFile) {
        int cut = dosFile.lastIndexOf('\\');
        if (cut <= 2) return dosFile.substring(0, 2) + "\\.";
        return dosFile.substring(0, cut);
    }

    /**
     * DOS 路径的启动命令引号包装：一律加引号。
     * 与 ProcessHelper.splitCommand（保留引号字符）+ wine argv 往返 +
     * winhandler CommandLineToArgvW 组合后，引号会被正确剥离（上游同构，
     * 已做逐字符仿真验证）；路径含空格时也保持单一参数。
     * 注意：不再使用 StringUtils.escapeDOSPath —— 其反斜杠翻倍与 "\\ "（反
     * 斜杠+空格）shell 风格转义在 DOS 路径语境下是多余且危险的。
     */
    private static String dosQuote(String dosPath) {
        if (dosPath == null || dosPath.isEmpty()) return "\"\"";
        return "\"" + dosPath + "\"";
    }

    private String getWineStartCommand(String exePath, String[] exeDos) {
        String args = "";
        String execArgs = envVars.get("EXTRA_EXEC_ARGS");
        envVars.remove("EXTRA_EXEC_ARGS");

        if (exePath != null && !exePath.isEmpty()) {
            if (exeDos != null) {
                // v13 主路径：winhandler（反汇编确认契约）lpFile=首个非旗标参数、
                // lpDirectory=/dir 值。传完整 DOS 路径后 wine 直接按绝对路径定位，
                // 不再依赖"裸文件名 + 工作目录"的解析链，任一环节损坏都不再致命。
                args += "/dir " + dosQuote(exeDos[1]) + " " + dosQuote(exeDos[0]);
            }
            else {
                // 兜底：沿用 v12 行为（目录映射 + 裸文件名）
                String exeDir = FileUtils.getDirname(exePath);
                String filename = FileUtils.getName(exePath);
                String dosDir = toDosPath(exeDir);
                if (dosDir == null) {
                    Log.w(TAG, "exe 目录无法映射容器盘符，回落 Z: 兜底: " + exeDir);
                    dosDir = "Z:" + exeDir.replace('/', '\\');
                }
                args += "/dir " + dosQuote(dosDir) + " " + dosQuote(filename);
            }
        }
        else {
            args += "\"winecfg\"";
        }
        if (!execArgs.isEmpty()) args += " " + execArgs;
        return args;
    }

    /**
     * v10：winhandler.exe 缺失时的兜底启动命令 —— 不经 winhandler，
     * 由 explorer 直接在虚拟桌面内启动目标 exe（游戏照常运行，仅缺少
     * winhandler 承担的 UDP 7947 窗口/进程信息与手柄通道）。
     * v13：优先使用已回查验证的完整 DOS 路径。
     */
    private String getDirectStartCommand(String exePath, String[] exeDos) {
        if (exePath == null || exePath.isEmpty()) return "\"winecfg\"";
        if (exeDos != null) return dosQuote(exeDos[0]);
        String dosPath = toDosPath(exePath);
        if (dosPath == null) {
            Log.w(TAG, "exe 无法映射容器盘符，回落 Z: 兜底: " + exePath);
            dosPath = "Z:" + new File(exePath).getAbsolutePath().replace('/', '\\');
        }
        return dosQuote(dosPath);
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
        // v11 防御（容器启动失败 NumberFormatException: For input string: ""）：
        // 版本串可能为空（脏数据 "dxvk-" 经 parseNumber 产出 ""）或含非数字段
        // （gplasync 后缀版本经 parseNumber 产生尾随 '.' → split 出空段）。
        // 此前直接 Integer.parseInt 抛异常终止整个容器启动流程，现逐段安全解析。
        if (varA == null || varA.isEmpty()) varA = "0";
        if (varB == null || varB.isEmpty()) varB = "0";
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        int numA, numB;
        for (int i = 0; i < minLen; i++) {
            numA = parseIntSafe(levelsA[i]);
            numB = parseIntSafe(levelsB[i]);
            if (numA != numB) return numA - numB;
        }
        return levelsA.length - levelsB.length;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }
}
