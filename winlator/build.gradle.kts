plugins {
    id("com.android.library")
}

// ============================================================
// :winlator —— Winlator Cmod 7.1.4x 引擎模块（AnWind 集成版）
// ============================================================
// 来源：https://github.com/Pipetto-crypto/winlator （cmod 7.1.4x）
// 本次集成剔除的部分（显示端改由 AnWind X11 承担，见 x11/ 包）：
//   - xserver/（内置 X 协议服务器）        - renderer/ + XServerView
//   - XServerComponent / VirGLRenderer     - VortekRenderer
//   - UI 层（Fragment/Dialog/BigPicture/XR/steamgrid/saves/restore）
// 新增的部分：
//   - x11/X11DisplayComponent —— wine DISPLAY → lorie X server 桥
//   - session/WinlatorSession   —— 容器会话编排（替代 XServerDisplayActivity）
// 引擎大资产（imagefs.txz + proton-9.0-*.txz）不进 git，构建期由
// .github/workflows/build.yml 从 afeimod/AnWind winlator-rootfs release 下载。
// ============================================================

android {
    namespace = "com.winlator.cmod"
    compileSdk = 34

    defaultConfig {
        // v2.23 修复：恢复上游 minSdk 26 —— 模块 native 层（alsa_client.c 等）
        // 使用 AAudio API（API 26 引入），clang 可用性检查在 minSdk<26 时直接报
        // "unavailable: introduced in Android 26"。宿主 :app 仍保持 minSdk 24，
        // 由 app 的 AndroidManifest tools:overrideLibrary（逗号分隔）放行合并。
        minSdk = 26

        buildConfigField("String", "VERSION_NAME", "\"7.1.4x-cmod-anwind\"")

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_static"
            }
        }
        ndk {
            // 原版仅 arm64-v8a（imagefs 内 bionic wine/box64 二进制亦仅 arm64）
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // 压缩包解压（imagefs.txz = XZ；容器/组件包 = ZSTD），与原版一致
    // ============================================================
    // v2.24 修复（运行时闪退）：zstd-jni 必须用 @aar 打包
    // ============================================================
    // 现象：首次启动解压 pulseaudio.tzst 时闪退：
    //   UnsatisfiedLinkError: dlopen failed: library
    //   "libzstd-jni-1.5.2-3.so" not found
    //   Unsupported OS/arch, cannot find /linux/aarch64/libzstd-jni-1.5.2-3.so
    // 根因：纯 jar 的原生库是桌面 glibc 版（jar 内资源 /linux/aarch64/…）：
    //   1) AGP 不会把依赖 jar 的资源文件打进 APK → getResourceAsStream 找不到；
    //   2) 即使打进去，glibc 版 .so 在 Android(bionic) 上也无法 dlopen；
    //   3) System.loadLibrary("zstd-jni-1.5.2-3") 在 APK lib/<abi>/ 里无库可加载。
    // 修复：改用 @aar（官方 Android 原生包，NDK r19 编译、minSdk 16，
    //   含 arm64-v8a/armeabi-v7a/x86/x86_64 的 libzstd-jni-1.5.2-3.so，
    //   文件名与 loadLibrary 查找名完全一致），随 APK lib/<abi>/ 打包。
    api("com.github.luben:zstd-jni:1.5.2-3@aar")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    // WinHandler/外部手柄的偏好读取
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("androidx.collection:collection:1.2.0")
}
