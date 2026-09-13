package com.winlator.cmod.x11;

import android.content.Context;

import java.io.File;

/**
 * AnWind X server（termux-x11/lorie）socket 定位器。
 *
 * lorie X server 由 app_process 子进程承载（终端 `anwind-x11 :1` 或
 * Winlator 容器会话自动拉起），其 X11 Unix socket 落在宿主自己的
 * tmp 目录：/data/data/<pkg>/files/usr/tmp/.X11-unix/X<n>。
 * （与 glibc-runner 的 DISPLAY 自动探测逻辑保持一致。）
 */
public abstract class X11SocketFinder {
    private static final String[] DISPLAY_CANDIDATES = {"1", "0", "2", "3"};

    /** 返回第一个存在的 X socket 绝对路径；找不到返回 null。 */
    public static String findSocket(Context context) {
        for (String dir : candidateDirs(context)) {
            File base = new File(dir);
            if (!base.isDirectory()) continue;
            for (String n : DISPLAY_CANDIDATES) {
                File sock = new File(base, "X" + n);
                if (sock.exists()) return sock.getPath();
            }
        }
        return null;
    }

    /** 返回第一个存在的显示号（无则返回默认 "1"）。 */
    public static String findDisplayNumber(Context context) {
        for (String dir : candidateDirs(context)) {
            File base = new File(dir);
            if (!base.isDirectory()) continue;
            for (String n : DISPLAY_CANDIDATES) {
                if (new File(base, "X" + n).exists()) return n;
            }
        }
        return DISPLAY_CANDIDATES[0];
    }

    public static String[] candidateDirs(Context context) {
        String prefix = "/data/data/" + context.getPackageName() + "/files/usr";
        return new String[]{
            prefix + "/tmp/.X11-unix",
            "/tmp/.X11-unix",
            System.getenv("TMPDIR") == null ? "" : (System.getenv("TMPDIR") + "/.X11-unix")
        };
    }

    /** $PREFIX 路径（App 自身数据目录，与终端脚本约定一致）。 */
    public static String getPrefixPath(Context context) {
        return "/data/data/" + context.getPackageName() + "/files/usr";
    }
}
