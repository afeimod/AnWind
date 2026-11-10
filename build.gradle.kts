// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 1.9.25 + KSP 1.9.25-1.0.20（官方配对，勿再改动）
    // v2.23.0 勘误：v2.20.1 的「负缓存」诊断是错的——出事那次运行
    // Gradle 缓存恢复本身失败（Cache service responded 400），home 为空，
    // 不存在任何缓存条目；且 1.9.25-1.0.20 在 Maven Central / aliyun /
    // Plugin Portal 均实际存在（curl 实测 200）。真正根因是 GitHub
    // Actions 服务降级导致出站解析集体失败，已由 build.yml 的三段重试 +
    // --refresh-dependencies 兜底。版本保持官方配对不动。
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}
