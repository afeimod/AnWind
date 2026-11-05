package com.anwind.apps.containers.launcher

import java.io.File

/**
 * v3.3.3：/opt/conf 生态配置读写（用户问题 5："容器编辑所有东西都没有
 * 读取 opt 下的相关配置，也无法更改选项来改变 opt 下的配置"）。
 *
 * 语义对照 wineguanli.py（PyQt 容器管理器，参考实现）：
 *  - env.conf / locale.conf / cores.conf 均为 sh source 语义的行式配置：
 *    `export KEY="VALUE"`（locale.conf 是单行裸值）；
 *  - 写 = 逐行扫描替换同名键，无则文件尾追加（save_mangohud_config
 *    同款 replace-or-append）；目录不存在自动创建（Path("/opt/conf")
 *    .mkdirs 同款）；删 = 剔除同名行后回写；
 *  - 解析仅识别 `KEY=VALUE` / `export KEY=VALUE` 行（与启动链
 *    [WineEnvironment.readOptConfs] 同源容错），引号剥离。
 *
 * 路径解析复用 [WineEnvironment.optRoot]（与启动链完全同源 —— UI 里改
 * 的就是启动链 source 的那一份，杜绝"改了不生效"）。
 * 全部方法在调用方线程执行（UI 侧自行 Dispatchers.IO）；原子落盘
 * （tmp + rename，失败回退 copyTo）。
 */
object OptConfSupport {

    /** /opt 生态根；未安装生态包时 null（UI 显示"未检测到 /opt 生态"）。 */
    fun root(): File? = WineEnvironment.optRoot(null)

    /** /opt/conf 目录；生态缺失时 null。 */
    fun confDir(): File? = root()?.let { File(it, "conf") }

    // ------------------------------------------------------------------
    // env.conf：通用 KEY=VALUE 读写
    // ------------------------------------------------------------------

    /** env.conf 现有条目（按文件顺序；无文件/无生态 → 空）。 */
    fun envEntries(): List<Pair<String, String>> {
        val f = confFile("env.conf") ?: return emptyList()
        return parseConfLines(f)
    }

    /**
     * 写入/更新 env.conf 的一个键（replace-or-append）。
     * @return null 成功；非 null 为错误消息。
     */
    fun writeEnvKey(key: String, value: String): String? {
        val k = key.trim()
        if (k.isEmpty() || !Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(k)) {
            return "无效变量名: \"$key\"（仅允许字母/数字/下划线，且不以数字开头）"
        }
        return rewriteConf("env.conf") { lines ->
            val quoted = "\"${value.trim()}\""
            var replaced = false
            for (i in lines.indices) {
                val existing = keyOf(lines[i]) ?: continue
                if (existing == k) {
                    lines[i] = "export $k=$quoted"
                    replaced = true
                }
            }
            if (!replaced) lines.add("export $k=$quoted")
        }
    }

    /** 删除 env.conf 的一个键；@return null 成功 / 错误消息。 */
    fun removeEnvKey(key: String): String? =
        rewriteConf("env.conf") { lines ->
            lines.removeAll { keyOf(it) == key }
        }

    // ------------------------------------------------------------------
    // cores.conf：PRIMARY_CORES（CPU 核心绑定，如 "0-3" / "4,5"）
    // ------------------------------------------------------------------

    fun primaryCores(): String? {
        val f = confFile("cores.conf") ?: return null
        return parseConfLines(f).firstOrNull { it.first == "PRIMARY_CORES" }?.second
    }

    fun writePrimaryCores(value: String): String? =
        rewriteConf("cores.conf") { lines ->
            val quoted = "\"${value.trim()}\""
            var replaced = false
            for (i in lines.indices) {
                if (keyOf(lines[i]) == "PRIMARY_CORES") {
                    lines[i] = "export PRIMARY_CORES=$quoted"
                    replaced = true
                }
            }
            if (!replaced) lines.add("export PRIMARY_CORES=$quoted")
        }

    // ------------------------------------------------------------------
    // locale.conf：单行裸值（语言）
    // ------------------------------------------------------------------

    /** 当前 locale.conf 值（复用启动链同源解析；无生态 → null）。 */
    fun locale(): String? = WineEnvironment.readOptLocale(null)

    fun writeLocale(value: String): String? {
        val root = root() ?: return "未检测到 /opt 生态"
        return try {
            val f = File(root, "locale.conf")
            val tmp = File(f.parentFile, f.name + ".anwind-tmp")
            tmp.writeText(value.trim() + "\n")
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------
    // 内部：行式 conf 解析 / 原子回写
    // ------------------------------------------------------------------

    private fun confFile(name: String): File? =
        confDir()?.let { d -> File(d, name).takeIf { it.isFile } }

    /** 解析 `KEY=VALUE` / `export KEY=VALUE` 行（引号剥离；忽略注释/空行）。 */
    private fun parseConfLines(f: File): List<Pair<String, String>> =
        runCatching {
            f.readLines().mapNotNull { raw ->
                var line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                if (line.startsWith("export ")) line = line.substring(7).trim()
                val eq = line.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val k = line.substring(0, eq).trim()
                var v = line.substring(eq + 1).trim()
                if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
                    v = v.substring(1, v.length - 1)
                }
                if (k.isEmpty()) null else k to v
            }
        }.getOrDefault(emptyList())

    private fun keyOf(line: String): String? {
        var l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        if (l.startsWith("export ")) l = l.substring(7).trim()
        val eq = l.indexOf('=')
        if (eq <= 0) return null
        val k = l.substring(0, eq).trim()
        return k.ifEmpty { null }
    }

    /**
     * 行级转换 + 原子回写（tmp + rename；跨设备 rename 失败回退 copyTo）。
     * @return null 成功；非 null 错误消息。
     */
    private fun rewriteConf(name: String, transform: (MutableList<String>) -> Unit): String? {
        val dir = confDir() ?: return "未检测到 /opt 生态"
        return try {
            dir.mkdirs()
            val f = File(dir, name)
            val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
            transform(lines)
            val tmp = File(dir, "$name.anwind-tmp")
            tmp.writeText(lines.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
