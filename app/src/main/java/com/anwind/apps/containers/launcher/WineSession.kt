package com.anwind.apps.containers.launcher

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 原生 Wine 会话 —— 替代旧链路的 headless TerminalSession。
 *
 * 一个会话 = 一个 [NativeShellExecutor]（承载 wine 客户端进程）+ 一份
 * 会话日志（内存滚动缓冲 + 可选落盘文件）。生命周期语义：
 *
 *  - **日志**：启动链决策行、子进程 stdout/stderr 逐行、退出码 —— 全部
 *    实时追加（旧的 pty transcript 二次抠取不存在了）；
 *  - **退出**：自然退出（游戏/程序结束）或 [stop]（用户点"停止"）；
 *    会话只负责 wine 客户端进程本身，wineserver 整树收尾由
 *    [WineServerUtils] 在停止链路中完成（对齐 WinFusion 的
 *    shellExecutor → stopWineServer 顺序）。
 *
 * @property name 会话键（容器名或 "__install__"）
 */
class WineSession internal constructor(
    val name: String,
    val executor: NativeShellExecutor,
    internal val sink: SessionLogSink
) {

    /** 会话进程是否仍在运行。 */
    val isRunning: Boolean get() = executor.isRunning

    /** 强制结束 wine 客户端进程（不等待）。 */
    fun finishIfRunning() {
        runCatching { executor.stop(true) }
    }

    /** 强制结束并短暂等待（1 秒，对齐 WinFusion shell 收尾节奏）。 */
    fun stopAndWait() {
        runCatching { executor.stopAndWait(true, 1, TimeUnit.SECONDS) }
    }

    /** 追加一行会话日志（stdout/stderr 回调线程调用）。 */
    fun writeLog(line: String) = sink.log(line)

    override fun toString(): String = "WineSession(name=$name, running=$isRunning)"
}

/**
 * 会话日志接收器：内存滚动缓冲（供 UI "Wine 输出"面板）+ 可选文件落盘。
 *
 * 文件写全部为追加写、按行 flush，多线程（stdout/stderr/决策行）经
 * [fileLock] 串行化；目录不可写时静默降级为纯内存（对齐旧版零阻断原则）。
 */
internal class SessionLogSink(
    val key: String,
    val logFile: File?
) {

    private val buffer = StringBuilder()
    private val fileLock = Any()

    /** 注册到 RootfsLauncher.lastLogs 的内存缓冲（UI 读取）。 */
    val text: StringBuilder get() = buffer

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** 追加一行（自动补换行）。 */
    fun log(line: String) {
        synchronized(buffer) {
            buffer.append(line).append('\n')
            if (buffer.length > MAX_BUFFER) {
                val trimmed = buffer.substring(buffer.length - MAX_BUFFER / 2)
                buffer.setLength(0)
                buffer.append(trimmed)
            }
        }
        logFile?.let { f ->
            synchronized(fileLock) {
                runCatching { f.appendText(line + "\n") }
            }
        }
    }

    /** 带时间戳的决策行（启动链各步骤）。 */
    fun stamp(line: String) = log("[${stamp()}] $line")

    companion object {
        private const val MAX_BUFFER = 16384
    }
}
