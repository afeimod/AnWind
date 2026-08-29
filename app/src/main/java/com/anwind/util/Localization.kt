package com.anwind.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * v2.14 应用内双语（中文 / English）。
 *
 * 设计：key = 中文原文，en 字典 = 中文 → 英文。
 * - [L]（Composable）：读取 [LocalAppLanguage]，en-US 模式下查字典，查不到回退中文原文
 * - [L10n.t]（非 Composable，如 Toast / WebView 回调）：读取全局同步的 [L10n.current]
 *
 * 覆盖范围：设置中心导航/分区/主要卡片、个性化页、时间与语言页、应用页、
 * 开始菜单、桌面右键菜单、任务栏、锁屏等高频界面；
 * 未收录词条（各内置应用内部文案）回退中文，后续版本逐步补充。
 */
object L10n {

    /** 全局语言状态（供非 Composable 环境读取；由 MainActivity 每次重组同步） */
    @Volatile
    var current: String = "zh-CN"

    /** 非组合环境的取词（Toast / 回调里用） */
    fun t(text: String): String =
        if (current == "en-US") en[text] ?: text else text

    val en: Map<String, String> = mapOf(
        // ===== 通用 =====
        "设置" to "Settings",
        "确定" to "OK",
        "取消" to "Cancel",
        "保存" to "Save",
        "重置" to "Reset",
        "开启" to "On",
        "关闭" to "Off",
        "已开启" to "On",
        "已关闭" to "Off",
        "搜索" to "Search",
        "查找设置" to "Find a setting",

        // ===== 设置中心：左侧导航分区 =====
        "系统" to "System",
        "蓝牙和设备" to "Bluetooth & devices",
        "蓝牙和其他设备" to "Bluetooth & other devices",
        "网络和 Internet" to "Network & internet",
        "个性化" to "Personalization",
        "应用" to "Apps",
        "账户" to "Accounts",
        "时间和语言" to "Time & language",
        "时间与语言" to "Time & language",
        "游戏" to "Gaming",
        "辅助功能" to "Accessibility",
        "隐私和安全" to "Privacy & security",
        "隐私和安全性" to "Privacy & security",
        "Windows 更新" to "Windows Update",
        "关于" to "About",

        // ===== 设置中心：分区标题/副标题 =====
        "显示、声音、通知、电源" to "Sound, display, notifications, power",
        "声音、显示、通知、电源" to "Sound, display, notifications, power",
        "设备管理、蓝牙、打印机" to "Devices, Bluetooth, printers",
        "Wi-Fi、移动网络、飞行模式、数据使用量" to "Wi-Fi, mobile networks, airplane mode, data usage",
        "背景、颜色、主题、锁屏界面" to "Background, colors, themes, lock screen",
        "已安装的应用、默认应用、可选功能" to "Installed apps, defaults, optional features",
        "您的账户、登录选项" to "Your accounts, sign-in options",
        "账户信息、登录选项、邮箱、同步" to "Accounts, sign-in, email, sync",
        "语音、区域、日期" to "Speech, region, date",
        "日期和时间、语言区域、输入" to "Date & time, language & region, typing",
        "Xbox Game Bar、游戏模式、屏幕捕获" to "Xbox Game Bar, game mode, capture",
        "视觉、听觉、交互辅助" to "Vision, hearing, interaction",
        "Windows 安全中心、权限" to "Windows Security, permissions",
        "位置、相机、麦克风、诊断" to "Location, camera, microphone, diagnostics",
        "更新、备份、恢复" to "Update, backup, recovery",
        "获取最新更新、安全补丁、新功能" to "Latest updates, security patches, new features",
        "AnWind 系统信息" to "About AnWind",

        // ===== 个性化 =====
        "主题" to "Themes",
        "背景" to "Background",
        "深浅模式对所有 Windows 主题生效" to "Color mode applies to all Windows themes",
        "自动隐藏任务栏" to "Auto-hide the taskbar",
        "壁纸" to "Wallpaper",
        "从文件资源管理器选择图片" to "Pick an image from File Explorer",
        "当前壁纸：自定义" to "Current: custom",
        "当前：主题默认" to "Current: theme default",
        "恢复默认壁纸" to "Restore default wallpaper",
        "使用主题自带的默认壁纸" to "Use the theme's built-in wallpaper",
        "设为桌面壁纸" to "Set as desktop wallpaper",
        "已设为壁纸" to "Wallpaper updated",
        "颜色" to "Colors",
        "深浅模式" to "Color mode",
        "跟随主题" to "Follow theme",
        "浅色" to "Light",
        "深色" to "Dark",
        "强调色" to "Accent color",
        "主题自带" to "Theme default",
        "锁屏界面" to "Lock screen",
        "点击立即锁定，上滑解锁" to "Tap to lock now, swipe up to unlock",
        "立即锁定" to "Lock now",
        "字体" to "Fonts",
        "字体大小" to "Font size",
        "字体颜色" to "Font color",
        "字体样式" to "Font style",
        "跟随主题色" to "Follow theme",
        "白色" to "White",
        "黑色" to "Black",
        "无衬线（默认）" to "Sans-serif (default)",
        "衬线" to "Serif",
        "等宽" to "Monospace",
        "预览：AnWind Windows 桌面体验 AaBbCc 123" to "Preview: AnWind desktop AaBbCc 123",
        "任务栏" to "Taskbar",
        "居中" to "Centered",
        "左对齐" to "Left-aligned",
        "任务栏图标对齐方式" to "Taskbar icon alignment",
        "Win11 居中风格" to "Win11 centered style",
        "经典靠左风格" to "Classic left-aligned",

        // ===== 时间与语言 =====
        "语言和区域" to "Language & region",
        "显示语言" to "Display language",
        "中文" to "中文",
        "English" to "English",
        "语言已切换" to "Language changed",
        "已切换为 English，主要界面即时生效" to "Switched to English; main UI updates instantly",
        "已切换为中文，主要界面即时生效" to "已切换为中文，主要界面即时生效",
        "切换后设置中心、开始菜单、任务栏等主要界面即时生效" to "Settings, Start menu, taskbar and other main UI switch instantly",
        "输入" to "Typing",
        "键盘、字典、自动更正（打开系统输入法设置）" to "Keyboard, dictionary, autocorrect (system settings)",

        // ===== 应用 =====
        // （v2.14.3 清理：旧渲染模式设置相关词条已随 v2.14 灰屏 hack 一并移除）
        "已安装应用" to "Installed apps",
        "浏览器 UA 模式" to "Browser UA mode",
        "桌面模式" to "Desktop mode",
        "手机模式" to "Mobile mode",

        // ===== 开始菜单 =====
        "所有应用" to "All apps",
        "搜索应用和 Web" to "Search apps and web",
        "电源" to "Power",
        "锁定" to "Lock",
        "关闭所有窗口" to "Close all windows",
        "用户" to "User",
        "已固定" to "Pinned",

        // ===== 桌面右键菜单 =====
        "打开" to "Open",
        "重命名" to "Rename",
        "删除" to "Delete",
        "属性" to "Properties",
        "查看" to "View",
        "大图标" to "Large icons",
        "中等图标" to "Medium icons",
        "小图标" to "Small icons",
        "排序方式" to "Sort by",
        "默认" to "Default",
        "名称" to "Name",
        "类型" to "Type",
        "刷新" to "Refresh",
        "新建快捷方式" to "New shortcut",
        "切换主题" to "Switch theme",
        "显示设置" to "Display settings",
        "个性化设置" to "Personalize",
        "打开终端" to "Open terminal",
        "任务管理器" to "Task Manager",

        // ===== 锁屏 =====
        "上滑或点击解锁" to "Swipe up or tap to unlock",
        "已锁定" to "Locked",

        // ===== 桌面图标拖动 =====
        "拖动排序" to "Drag to reorder"
    )
}

/** 当前应用语言（"zh-CN" / "en-US"），由 MainActivity 顶层提供 */
val LocalAppLanguage = staticCompositionLocalOf { "zh-CN" }

/** Composable 取词：en-US 时查字典，缺省回退中文原文 */
@Composable
fun L(text: String): String {
    val lang = LocalAppLanguage.current
    return if (lang == "en-US") L10n.en[text] ?: text else text
}
