package com.termux.x11;

import static android.system.Os.getuid;
import static android.system.Os.getenv;

import android.annotation.SuppressLint;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.Executors;

@Keep @SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START";
    static final Handler handler;
    public static Context ctx;
    private final Intent intent = createIntent();

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        android.util.Log.i("CmdEntryPoint", "commit " + BuildConfig.COMMIT);
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        if (!start(args)) {
            // AnWind 排障适配：start() 失败原先完全静默（终端侧
            // anwind-x11.log 被重定向后为空文件，无从定位）。原因细节由
            // native 层写 logcat，这里镜像一条到 stderr，随 anwind-x11
            // 的重定向落入启动日志。
            System.err.println("[anwind-x11] X server 初始化失败（start 返回 false）。"
                + "常见原因：显示号 socket 被残留占用（先执行 anwind-x11-stop）、"
                + "XDG_RUNTIME_DIR 不可写；详细原因见 logcat（anwind-x11 失败时会自动附带摘要）。");
            System.exit(1);
        }

//        spawnListeningThread();
        sendBroadcastDelayed();
    }

    @SuppressLint({"WrongConstant", "PrivateApi"})
    private Intent createIntent() {
        String targetPackage = getenv("TERMUX_X11_OVERRIDE_PACKAGE");
        if (targetPackage == null)
            targetPackage = "com.termux";
        // We should not care about multiple instances, it should be called only by `Termux:X11` app
        // which is single instance...
        Bundle bundle = new Bundle();
        bundle.putBinder(null, this);

        Intent intent = new Intent(ACTION_START);
        intent.putExtra(null, bundle);
        intent.setPackage(targetPackage);

        if (getuid() == 0 || getuid() == 2000)
            intent.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);

        return intent;
    }

    private void sendBroadcast() {
        sendBroadcast(intent);
    }

    static void sendBroadcast(Intent intent) {
        try {
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            if (e instanceof NullPointerException && ctx == null)
                Log.i("Broadcast", "Context is null, falling back to manual broadcasting");
            else
                Log.e("Broadcast", "Falling back to manual broadcasting, failed to broadcast intent through Context:", e);

            String packageName;
            try {
                packageName = android.app.ActivityThread.getPackageManager().getPackagesForUid(getuid())[0];
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            IActivityManager am;
            try {
                //noinspection JavaReflectionMemberAccess
                am = (IActivityManager) android.app.ActivityManager.class
                    .getMethod("getService")
                    .invoke(null);
            } catch (Exception e2) {
                try {
                    am = (IActivityManager) Class.forName("android.app.ActivityManagerNative")
                        .getMethod("getDefault")
                        .invoke(null);
                } catch (Exception e3) {
                    throw new RuntimeException(e3);
                }
            }

            assert am != null;
            IIntentSender sender = am.getIntentSender(1, packageName, null, null, 0, new Intent[] { intent },
                null, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT, null, 0);
            try {
                //noinspection JavaReflectionMemberAccess
                IIntentSender.class
                    .getMethod("send", int.class, Intent.class, String.class, IBinder.class, IIntentReceiver.class, String.class, Bundle.class)
                    .invoke(sender, 0, intent, null, null, new IIntentReceiver.Stub() {
                        @Override public void performReceive(Intent i, int r, String d, Bundle e, boolean o, boolean s, int a) {}
                    }, null, null);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    // In some cases Android Activity part can not connect opened port.
    // In this case opened port works like a lock file.
    private void sendBroadcastDelayed() {
        if (!connected())
            sendBroadcast(intent);

        handler.postDelayed(this::sendBroadcastDelayed, 1000);
    }

    void spawnListeningThread() {
        new Thread(this::listenForConnections).start();
    }

    /** @noinspection DataFlowIssue*/
    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        Context context;
        PrintStream err = System.err;
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            // Hiding harmless framework errors, like this:
            // java.io.FileNotFoundException: /data/system/theme_config/theme_compatibility.xml: open failed: ENOENT (No such file or directory)
            System.setErr(new PrintStream(new OutputStream() { public void write(int arg0) {} }));
            if (System.getenv("OLD_CONTEXT") != null) {
                context = android.app.ActivityThread.systemMain().getSystemContext();
            } else {
                context = ((android.app.ActivityThread) Class.
                    forName("sun.misc.Unsafe").
                    getMethod("allocateInstance", Class.class).
                    invoke(unsafe, android.app.ActivityThread.class))
                    .getSystemContext();
            }
        } catch (Exception e) {
            Log.e("Context", "Failed to instantiate context:", e);
            context = null;
        } finally {
            System.setErr(err);
        }
        return context;
    }

    public static native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    private static native boolean connected();
    private native void listenForConnections();

    static {
        try {
            if (Looper.getMainLooper() == null)
                Looper.prepareMainLooper();
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Something went wrong when preparing MainLooper", e);
            // AnWind 排障适配：镜像到 stderr（落入 anwind-x11.log）
            System.err.println("[anwind-x11] MainLooper 初始化异常: " + e);
        }
        handler = new Handler();
        ctx = createContext();

        String path = "lib/" + Build.SUPPORTED_ABIS[0] + "/libXlorie.so";
        ClassLoader loader = CmdEntryPoint.class.getClassLoader();
        URL res = loader != null ? loader.getResource(path) : null;
        String libPath = res != null ? res.getFile().replace("file:", "") : null;
        // AnWind 适配：宿主 APK 以 useLegacyPackaging=true 打包（anwind-reprefix
        // 可执行需要解压到 nativeLibraryDir 才能被 shell 调用），lib/ 条目在 APK
        // 内被压缩，无法直接 dlopen。客户端脚本会经 anwind-x11.env 注入宿主
        // nativeLibraryDir，此处兜底从解压目录加载 libXlorie.so。
        String anwindNativeDir = getenv("ANWIND_X11_NATIVE_DIR");
        if (libPath != null) {
            try {
                System.load(libPath);
            } catch (Exception e) {
                if (anwindNativeDir != null) {
                    try {
                        System.load(anwindNativeDir + "/libXlorie.so");
                    } catch (Exception e2) {
                        Log.e("CmdEntryPoint", "Failed to dlopen " + anwindNativeDir + "/libXlorie.so", e2);
                        System.err.println("Failed to load native library from " + anwindNativeDir + ". Did you install the right apk?");
                        System.exit(134);
                    }
                } else {
                    Log.e("CmdEntryPoint", "Failed to dlopen " + libPath, e);
                    System.err.println("Failed to load native library. Did you install the right apk? Try the universal one.");
                    System.exit(134);
                }
            }
        } else if (anwindNativeDir != null) {
            try {
                System.load(anwindNativeDir + "/libXlorie.so");
            } catch (Exception e2) {
                Log.e("CmdEntryPoint", "Failed to dlopen " + anwindNativeDir + "/libXlorie.so", e2);
                System.err.println("Failed to load native library from " + anwindNativeDir + ". Did you install the right apk?");
                System.exit(134);
            }
        } else {
            // It is critical only when it is not running in Android application process
            if (MainActivity.getInstance() == null) {
                System.err.println("Failed to acquire native library. Did you install the right apk? Try the universal one.");
                System.exit(134);
            }
        }
    }
}
