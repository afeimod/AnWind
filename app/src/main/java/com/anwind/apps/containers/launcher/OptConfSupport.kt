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

    /**
     * v3.3.4（用户问题 1）：cores.conf 双键回写 —— wineguanli.py
     * save_advanced_settings 同款（PRIMARY_CORES + SECONDARY_CORES 两行），
     * 更多设置·CPU核心页保存时同步生态包 conf，/opt 生态脚本与
     * 启动链（readOptConfs source）都能读到同一份。
     */
    fun writeCores(primary: String, secondary: String): String? =
        rewriteConf("cores.conf") { lines ->
            val p = "export PRIMARY_CORES=\"${primary.trim()}\""
            val s = "export SECONDARY_CORES=\"${secondary.trim()}\""
            var fp = false
            var fs = false
            for (i in lines.indices) {
                when (keyOf(lines[i])) {
                    "PRIMARY_CORES" -> { lines[i] = p; fp = true }
                    "SECONDARY_CORES" -> { lines[i] = s; fs = true }
                }
            }
            if (!fp) lines.add(p)
            if (!fs) lines.add(s)
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
    // DY 配置（v3.3.4，用户问题 2：wineguanli.py DynarecEditor 同源移植）
    //   custom.conf         —— 变量定义表：export VAR=候选值1,候选值2…
    //   dynarec_active.conf —— 当前生效值：export VAR=值
    //   UI 按定义表渲染（0/1 → 启用勾选；多值 → 下拉），应用时整文件
    //   重写 active.conf（wineguanli apply_config 同款），启动链
    //   readOptConfs 按固定顺序 source 它 —— 改的就是启动用的那份。
    // ------------------------------------------------------------------

    /** custom.conf 缺省定义表（wineguanli.py DynarecEditor.default_config 逐字移植）。 */
    val DY_DEFAULT_CUSTOM_CONF = listOf(
        "export BOX64_DYNAREC_ALIGNED_ATOMICS=0,1",
        "export BOX64_DYNAREC_BIGBLOCK=0,1,2,3",
        "export BOX64_DYNAREC_FASTNAN=0,1",
        "export BOX64_DYNAREC_SAFEFLAGS=0,1,2",
        "export BOX64_DYNAREC_STRONGMEM=0,1,2,3",
        "export BOX64_DYNAREC_WAIT=0,1",
        "export BOX64_DYNAREC_X87DOUBLE=0,1",
        "export BOX64_DYNAREC_CALLRET=0,1",
        "export BOX64_DYNAREC_FASTROUND=0,1",
        "export BOX64_IGNOREINT3=0,1",
        "export BOX64_DYNAREC_FORWARD=128,256,512,1024",
        "export BOX64_AVX=0,1,2",
        "export BOX64_UNITYPLAYER=0,1",
        "export BOX64_DYNAREC_WEAKBARRIER=0,1,2",
        "export BOX86_DYNAREC_ALIGNED_ATOMICS=0,1",
        "export BOX86_DYNAREC_BIGBLOCK=0,1,2,3",
        "export BOX86_DYNAREC_FASTNAN=0,1",
        "export BOX86_DYNAREC_SAFEFLAGS=0,1,2",
        "export BOX86_DYNAREC_STRONGMEM=0,1,2,3",
        "export BOX86_DYNAREC_WAIT=0,1",
        "export BOX86_DYNAREC_X87DOUBLE=0,1",
        "export BOX86_DYNAREC_CALLRET=0,1",
        "export BOX86_DYNAREC_FASTROUND=0,1",
        "export BOX86_IGNOREINT3=0,1"
    ).joinToString("\n") + "\n"

    /** custom.conf 是否存在（不存在时 UI 可先落缺省定义表）。 */
    fun dyCustomExists(): Boolean = confFile("custom.conf") != null

    /** custom.conf 缺失时写入缺省定义表（wineguanli load_config 同款自建）。@return null 成功。 */
    fun ensureDyCustomConf(): String? {
        if (dyCustomExists()) return null
        val dir = confDir() ?: return "未检测到 /opt 生态"
        return try {
            dir.mkdirs()
            java.io.File(dir, "custom.conf").writeText(DY_DEFAULT_CUSTOM_CONF)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** custom.conf 变量定义（VAR → 候选值列表，保持文件顺序；无文件 → 空）。 */
    fun dyVariables(): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        val f = confFile("custom.conf") ?: return out
        parseConfLines(f).forEach { (k, v) ->
            val values = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (values.isNotEmpty() && k !in out) out[k] = values
        }
        return out
    }

    /** dynarec_active.conf 当前值（VAR → 值；无文件 → 空）。 */
    fun dyActive(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val f = confFile("dynarec_active.conf") ?: return out
        parseConfLines(f).forEach { (k, v) -> out[k] = v }
        return out
    }

    /**
     * 应用 DY 配置：整文件重写 dynarec_active.conf（wineguanli apply_config
     * 同款 —— 定义表内的变量逐行 export，表外变量不写）。
     * @return null 成功；否则错误消息。
     */
    fun writeDynarecActive(values: List<Pair<String, String>>): String? {
        val dir = confDir() ?: return "未检测到 /opt 生态"
        val allowed = dyVariables().keys
        return try {
            dir.mkdirs()
            val f = java.io.File(dir, "dynarec_active.conf")
            val tmp = java.io.File(dir, "dynarec_active.conf.anwind-tmp")
            val body = values.filter { it.first in allowed }
                .joinToString("\n") { (k, v) -> "export $k=$v" }
            tmp.writeText(if (body.isEmpty()) "" else body + "\n")
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** 添加变量定义（custom.conf 追加 export VAR=候选值）。@return null 成功。 */
    fun appendDyVariable(name: String, valuesCsv: String): String? =
        rewriteConf("custom.conf") { lines ->
            lines.add("export ${name.trim()}=${valuesCsv.trim()}")
        }

    /** 删除变量定义（custom.conf 剔除同名行）。@return null 成功。 */
    fun removeDyVariable(name: String): String? =
        rewriteConf("custom.conf") { lines ->
            lines.removeAll { keyOf(it) == name }
        }

    /** 恢复 custom.conf 缺省定义表（wineguanli reset_default 同款）。@return null 成功。 */
    fun resetDyCustomConf(): String? {
        val dir = confDir() ?: return "未检测到 /opt 生态"
        return try {
            dir.mkdirs()
            java.io.File(dir, "custom.conf").writeText(DY_DEFAULT_CUSTOM_CONF)
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
