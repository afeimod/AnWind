package com.anwind.core.desktop

/**
 * v3.4.0【性能大修】：主桌面 launcher 式降载开关。
 *
 * 背景（用户实测：bionic 方式速度不到 proot 容器一半）：
 * AnWind 的 X11 桌面窗口与假 Windows 桌面同栖一个 Activity/进程 ——
 * X11 游戏真全屏时，被覆盖的主桌面 Compose 树仍然活着：任务栏/托盘/
 * 锁屏层各挂一条 1Hz 时钟循环，每秒触发重组+重排；自动锁屏轮询每 5s
 * 一跳。这些主线程工作与 wine/box64 的游戏线程抢大核 —— 主桌面成了
 * X11 运行时的常驻 CPU 竞争者。
 *
 * 语义对齐手机桌面（launcher）：launcher 被全屏应用覆盖即让出一切
 * 后台刷新 —— **X11 运行时才是前台主进程**，主桌面按"桌面软件占用
 * 逻辑"退位：
 *  - 任务栏 1Hz 时钟：真全屏期间不写 tick（重组静止）
 *  - 系统托盘实时预览时钟：同上
 *  - 锁屏层 1Hz 时钟：同上（锁屏浮层本身在 X11 全屏期间不可见）
 *  - 自动锁屏轮询：X11 全屏会话期间不触发（游戏没有"桌面空闲"概念，
 *    锁屏盖在游戏上是双重伤害）
 *
 * 退出全屏（返回键）瞬间恢复 —— [update] 由 DesktopEnvironment 的
 * anyTrueFullscreen 计算（WindowManager 通知流，随 wmRevision 重组）。
 */
object DesktopPerfGovernor {

    /** X11 真全屏会话进行中 → 主桌面进入降载态。 */
    @Volatile
    var quiesced: Boolean = false
        private set

    /** 桌面是否应暂停自刷新（时钟等 1Hz 循环读此门禁）。 */
    fun shouldTick(): Boolean = !quiesced

    /** 由桌面环境按 anyTrueFullscreen 同步（SideEffect，主线程写）。 */
    fun update(x11TrueFullscreen: Boolean) {
        quiesced = x11TrueFullscreen
    }
}
