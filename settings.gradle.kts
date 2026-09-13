pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AnWind"
include(":app")
// v2.22.2 内置 X11 桌面：termux-x11 模块（lorie + Xwayland 预编译库）
// 及其框架隐藏 API 编译桩（compileOnly）
include(":termux-x11-stub")
include(":termux-x11")
// v2.23：Winlator Cmod 7.1.4x 引擎模块（容器/Wine/Box64/SysVSHM/ALSA），
// 显示端不使用其自带 XServer —— 由 :app 的 X11WindowController（lorie）承担
include(":winlator")
