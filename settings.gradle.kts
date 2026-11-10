// v2.23.0（2026-11-09 APK 构建事故修复）：仓库顺序按环境自适应。
//
// 【根因勘误】上一版把 aliyun 镜像提到最前，是按「负缓存」误诊做的「修复」。
// logs_115696791448.zip 实证：出事那次运行 Gradle 缓存恢复本身失败
// （Cache service responded with 400），Gradle home 是【空的】——负缓存理论
// 被证伪；KSP 1.9.25-1.0.20 在 Maven Central / aliyun public / Plugin Portal
// 三处均实际存在（curl 实测 200）。真正的根因是 GitHub Actions 服务降级
// （保存缓存时返回 Azure 边缘故障页 "Our services aren't available right
// now"），出站网络在同一窗口内对所有仓库集体失效。
//
// 【本版语义】Gradle 对「缺失模块」会遍历全部仓库，顺序只决定下载来源，
// 不影响 found/not-found；因此按【环境】排最优先源：
//   * GitHub CI（CI=true）：google/mavenCentral/PluginPortal 在前（就近、
//     CDN 稳定），aliyun 镜像殿后兜底 —— GH runner 访问 aliyun 绕地球半圈，
//     放最前只会拖慢每个 google 独占构件的探测。
//   * 本地（国内网络）：aliyun 在前加速，官方源殿后兜底。
// 构建级瞬时故障由 .github/workflows/build.yml v2.23.0 的三段重试 +
// --refresh-dependencies 兜底，仓库顺序不再承载「容错」职责。
pluginManagement {
    repositories {
        val onCI = System.getenv("CI") == "true"
        if (!onCI) {
            // 国内本地构建：aliyun 镜像优先（加速）
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        if (onCI) {
            // CI 兜底镜像（官方源故障/限流时仍可解析）
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val onCI = System.getenv("CI") == "true"
        if (!onCI) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        if (onCI) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

rootProject.name = "AnWind"
include(":app")
// v2.22.2 内置 X11 桌面：termux-x11 模块（lorie + Xwayland 预编译库）
// 及其框架隐藏 API 编译桩（compileOnly）
include(":termux-x11-stub")
include(":termux-x11")
