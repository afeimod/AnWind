package com.anwind.apps.containers

import java.io.File

/**
 * AnWind Wine 容器 — 管理器（App 侧）。
 *
 * 直接读写 rootfs 内的 container.conf / 目录结构，与终端里的
 * anwind-container CLI 共享同一份数据：App 里新建的容器，
 * 终端 `anwind-container list` 立即可见，反之亦然。
 *
 * 路径契约（与 bionic-rootfs/default.conf、anwind-container 一致）：
 *   rootfs 根   : /data/data/com.anwind/files/rootfs
 *   App 前缀    : /data/data/com.anwind/files/usr
 *   容器根      : <rootfs>/home/.anwind/containers
 *   全局配置    : <rootfs>/usr/etc/anwind/container.conf（default_container=<name>）
 *   X11 socket  : <App 前缀>/tmp/.X11-unix/Xn（显示方案为 X11，无 Weston）
 */
object ContainerManager {

    // ------------------------------------------------------------------
    // 路径常量（与 rootfs 侧脚本逐字对齐）
    // ------------------------------------------------------------------

    /** bionic rootfs 根目录。 */
    const val ROOTFS_ROOT = "/data/data/com.anwind/files/rootfs"

    /** App 自身前缀（termux-x11 X server、bash 所在）。 */
    const val APP_PREFIX = "/data/data/com.anwind/files/usr"

    /** 容器存储根目录。 */
    const val CONTAINERS_ROOT = "$ROOTFS_ROOT/home/.anwind/containers"

    /** 全局容器配置（default_container=...）。 */
    const val GLOBAL_CONF = "$ROOTFS_ROOT/usr/etc/anwind/container.conf"

    /** rootfs 侧容器管理 CLI（Kotlin 端经 bash 调用完成运行/安装类操作）。 */
    const val CLI = "$ROOTFS_ROOT/usr/bin/anwind-container"

    /** rootfs 侧 Wine 启动器。 */
    const val LAUNCHER = "$ROOTFS_ROOT/usr/bin/anwind-wine"

    /** App 侧 X11 socket 目录（显示方案为 X11）。 */
    const val X11_SOCK_DIR = "$APP_PREFIX/tmp/.X11-unix"

    /** rootfs 内 wine 多版本安装根（/usr/opt/，槽位 wine / wine-<名>）。 */
    const val WINE_OPT_DIR = "$ROOTFS_ROOT/usr/opt"

    /** 容器名约束：字母数字开头，仅字母/数字/点/下划线/连字符。 */
    private val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    // ------------------------------------------------------------------
    // 容器枚举与读取
    // ------------------------------------------------------------------

    /** 列出全部容器（按名称排序；跳过 logs 等非容器目录）。 */
    fun list(): List<ContainerData> {
        val root = File(CONTAINERS_ROOT)
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && f.name != "logs" }
            .orEmpty()
            .mapNotNull { ContainerData.fromConf(File(it, "container.conf")) }
            .sortedBy { it.name }
    }

    /** 按名取容器；不存在返回 null。 */
    fun get(name: String): ContainerData? =
        ContainerData.fromConf(File(File(CONTAINERS_ROOT, name), "container.conf"))

    /** 容器是否存在（目录 + conf 双条件）。 */
    fun exists(name: String): Boolean =
        get(name) != null

    /** 容器名合法性校验，返回 null 表示合法，否则返回错误信息。 */
    fun validateName(name: String): String? = when {
        name.isBlank() -> "容器名不能为空"
        !NAME_RE.matches(name) -> "容器名仅允许字母/数字/点/下划线/连字符（字母或数字开头）"
        exists(name) -> "同名容器已存在"
        else -> null
    }

    // ------------------------------------------------------------------
    // 容器创建 / 删除 / 克隆 / 更新
    // ------------------------------------------------------------------

    /**
     * 新建容器：目录 + WINEPREFIX + container.conf（与 CLI create 等效）。
     * @return null 成功；否则错误信息。
     */
    fun create(data: ContainerData): String? {
        validateName(data.name)?.let { return it }
        val dir = File(CONTAINERS_ROOT, data.name)
        return try {
            File(dir, "prefix").mkdirs()   // WINEPREFIX
            File(dir, "drive_c").mkdirs()  // 兼容旧布局占位
            File(dir, "container.conf").writeText(data.toConfText())
            null
        } catch (e: Exception) {
            "创建失败：${e.message}"
        }
    }

    /** 删除容器（整目录递归删除）。@return null 成功；否则错误信息。 */
    fun remove(name: String): String? {
        val dir = File(CONTAINERS_ROOT, name)
        if (!dir.isDirectory) return "容器不存在：$name"
        return try {
            // 先尝试终止容器内 wine 进程，避免文件占用
            WineSessionLauncher.stopContainerQuietly(name)
            dir.deleteRecursively()
            if (defaultName() == name) setDefault("")
            null
        } catch (e: Exception) {
            "删除失败：${e.message}"
        }
    }

    /**
     * 克隆容器（整目录复制 + 改写 conf 内 name=）。
     * @return null 成功；否则错误信息。
     */
    fun clone(src: String, dst: String): String? {
        validateName(dst)?.let { return it }
        val srcDir = File(CONTAINERS_ROOT, src)
        if (!srcDir.isDirectory) return "源容器不存在：$src"
        return try {
            val dstDir = File(CONTAINERS_ROOT, dst)
            srcDir.copyRecursively(dstDir, overwrite = true)
            val conf = File(dstDir, "container.conf")
            ContainerData.fromConf(conf)?.let { conf.writeText(it.copy(name = dst).toConfText()) }
            null
        } catch (e: Exception) {
            "克隆失败：${e.message}"
        }
    }

    /** 保存容器配置（保留原 created 时间戳）。@return null 成功；否则错误信息。 */
    fun update(data: ContainerData): String? {
        val conf = File(File(CONTAINERS_ROOT, data.name), "container.conf")
        if (!conf.isFile) return "容器不存在：${data.name}"
        return try {
            val created = ContainerData.fromConf(conf)?.created ?: data.created
            conf.writeText(data.copy(created = created).toConfText())
            null
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }

    /** 修改单个/多个键（k=v 语义，与 CLI set 等效）。@return null 成功；否则错误信息。 */
    fun setKeys(name: String, keys: Map<String, String>): String? {
        val data = get(name) ?: return "容器不存在：$name"
        var d = data
        keys["backend"]?.let { d = d.copy(backend = ContainerBackend.fromId(it)) }
        keys["preset"]?.let { d = d.copy(preset = Box64Preset.fromId(it)) }
        keys["screen"]?.let { d = d.copy(screen = it) }
        keys["dmode"]?.let { d = d.copy(dmode = DisplayMode.fromId(it)) }
        keys["gallium"]?.let { d = d.copy(gallium = it) }
        keys["env"]?.let { d = d.copy(env = it) }
        keys["wine"]?.let { d = d.copy(wine = it) }
        keys["lang"]?.let { d = d.copy(lang = it) }
        keys["audio"]?.let { d = d.copy(audio = ContainerAudio.fromId(it)) }
        return update(d)
    }

    // ------------------------------------------------------------------
    // Wine 多版本枚举（v2.26：/usr/opt/wine* 槽位，App 端选择器数据源）
    // ------------------------------------------------------------------

    /**
     * 扫描 rootfs 内已安装的 wine 版本（/usr/opt/ 下含 bin/wine 的目录）。
     *
     * 命名约定与 anwind-container install-wine 对齐：
     *   wine        主槽位（旧版兼容，无 default_wine 指针时的兑底）
     *   wine-<名>   多版本槽位（--name 指定）
     * 每个槽位的 .anwind-wine-info 记录 name/version/installed。
     */
    fun listWines(): List<WineInstall> {
        val opt = File(WINE_OPT_DIR)
        if (!opt.isDirectory) return emptyList()
        return opt.listFiles { f ->
            f.isDirectory && f.name.startsWith("wine") && File(f, "bin/wine").isFile
        }.orEmpty()
            .map { dir ->
                WineInstall(
                    name = dir.name,
                    dir = dir.absolutePath,
                    version = readWineInfo(dir, "version"),
                    installed = readWineInfo(dir, "installed")
                )
            }
            .sortedBy { it.name }
    }

    /** 全局默认 wine 槽位名（default_wine=；空 = 用主槽位 wine）。 */
    fun defaultWineName(): String {
        val f = File(GLOBAL_CONF)
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("default_wine=")) {
                return line.removePrefix("default_wine=").trim()
            }
        }
        return ""
    }

    private fun readWineInfo(dir: File, key: String): String {
        val f = File(dir, ".anwind-wine-info")
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("$key=")) return line.removePrefix("$key=").trim()
        }
        return ""
    }

    // ------------------------------------------------------------------
    // 默认容器（全局配置 default_container=<name>）
    // ------------------------------------------------------------------

    /** 读取默认容器名（无则空串）。 */
    fun defaultName(): String {
        val f = File(GLOBAL_CONF)
        if (!f.isFile) return ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("default_container=")) {
                return line.removePrefix("default_container=").trim()
            }
        }
        return ""
    }

    /** 设置默认容器（写全局 conf；空串清除）。@return null 成功；否则错误信息。 */
    fun setDefault(name: String): String? {
        if (name.isNotEmpty() && !exists(name)) return "容器不存在：$name"
        return try {
            val f = File(GLOBAL_CONF)
            f.parentFile?.mkdirs()
            val others = if (f.isFile) f.readLines().filter {
                val t = it.trim()
                t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("default_container=")
            } else emptyList()
            val body = others + if (name.isEmpty()) emptyList() else listOf("default_container=$name")
            f.writeText(
                buildString {
                    appendLine("# AnWind Wine 容器全局配置（anwind-container / App 端共用）")
                    if (body.isEmpty()) appendLine("# （暂无默认容器）")
                    body.forEach { appendLine(it) }
                }
            )
            null
        } catch (e: Exception) {
            "写入全局配置失败：${e.message}"
        }
    }

    /** 容器是否为默认容器。 */
    fun isDefault(name: String): Boolean =
        defaultName() == name

    // ------------------------------------------------------------------
    // 体检（与 CLI doctor 同源的本地快速检查，不做进程探测）
    // ------------------------------------------------------------------

    /**
     * App 端快速体检：X11 socket / rootfs / CLI / wine / box64 / FEX /
     * 容器数量。深度体检（音频、进程）用终端 `anwind-container doctor`。
     */
    fun doctor(): List<DoctorCheck> {
        val checks = mutableListOf<DoctorCheck>()

        // 1) rootfs 就位
        val rfs = File("$ROOTFS_ROOT/usr/bin")
        checks += DoctorCheck(
            ok = rfs.isDirectory,
            title = "bionic rootfs",
            detail = if (rfs.isDirectory) "$ROOTFS_ROOT/usr/bin 就绪"
                     else "未找到 $ROOTFS_ROOT/usr/bin，请先在 App 内导入 rootfs"
        )

        // 2) X11 socket（显示方案：X11）
        val sockDir = File(X11_SOCK_DIR)
        val sock = sockDir.listFiles { f -> f.name.startsWith("X") && f.name != "X0" }
        val hasSock = sock != null && sock.isNotEmpty()
        checks += DoctorCheck(
            ok = hasSock,
            title = "X11 服务（X11 socket）",
            detail = if (hasSock)
                    "检测到 ${X11_SOCK_DIR}/${sock.first().name}，DISPLAY=:${
                        sock.first().name.removePrefix("X")
                    }"
                else "未发现 X11 socket（$X11_SOCK_DIR/Xn）。请先打开 X11 桌面窗口或运行 anwind-x11 :1"
        )

        // 3) 容器 CLI（v2.26 起由 APK assets/anwind/scripts 启动时覆盖部署）
        val cli = File(CLI)
        checks += DoctorCheck(
            ok = cli.isFile && cli.canExecute(),
            title = "容器管理 CLI",
            detail = if (cli.isFile) "anwind-container / anwind-wine 已就绪（APK 内置脚本自动覆盖部署）"
                     else "脚本尚未部署（rootfs 未导入）。导入 rootfs 后打开一次主界面，APK 会自动覆盖安装"
        )

        // 4) wine 主程序（多版本：bionic 双形态 / 普通 Wine / Proton）
        val wines = listWines()
        val wine = File("$ROOTFS_ROOT/usr/opt/wine/bin/wine")
        checks += DoctorCheck(
            ok = wine.isFile || wines.isNotEmpty(),
            title = "Wine 运行时（多版本）",
            detail = when {
                wine.isFile && wines.size <= 1 -> "/usr/opt/wine 就绪"
                wines.isNotEmpty() -> "已装 ${wines.size} 个版本（${wines.joinToString("、") { it.name }}），默认：${defaultWineName().ifEmpty { "wine" }}"
                else -> "未安装 wine：终端 anwind-container install-wine <类别>（wine-catalog 查看 bionic/普通Wine/Proton 在线构建），或 App 端 Wine 选择器下方分类一键安装"
            }
        )

        // 5) box64
        val box64 = listOf(
            "$ROOTFS_ROOT/usr/local/bin/box64",
            "$ROOTFS_ROOT/usr/bin/box64",
            "$APP_PREFIX/bin/box64"
        ).map(::File).firstOrNull { it.isFile && it.canExecute() }
        checks += DoctorCheck(
            ok = box64 != null,
            title = "Box64 后端",
            detail = box64?.let { "box64 就绪：${it.absolutePath}" }
                ?: "未安装 box64（容器仍可用 hangover/fexcore/native 后端）"
        )

        // 6) FEX（可选）
        val fex = File("$ROOTFS_ROOT/usr/opt/fex/bin/FEXInterpreter")
        checks += DoctorCheck(
            ok = fex.isFile,
            title = "FEXCore 后端（可选）",
            detail = if (fex.isFile) "FEXInterpreter 就绪"
                     else "未安装 FEX-Emu（bionic 无上游构建，可忽略）"
        )

        // 7) 容器数量
        val n = list().size
        checks += DoctorCheck(
            ok = n > 0,
            title = "容器",
            detail = if (n > 0) "共 $n 个容器，默认：${defaultName().ifEmpty { "未设置" }}"
                     else "尚无容器，先新建一个"
        )

        return checks
    }

    /** rootfs 是否已导入（多个入口共用的前置判断）。 */
    fun rootfsReady(): Boolean = File(CLI).isFile
}
