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
// 引擎大资产（imagefs.txz 等 3 个 LFS 文件）不进 git，构建期由
// .github/workflows/build.yml 从 Pipetto-crypto/winlator 拉取补齐。
// ============================================================

android {
    namespace = "com.winlator.cmod"
    compileSdk = 34

    defaultConfig {
        minSdk = 24  // 与宿主 :app 对齐（原版 26；低版本设备运行 wine 可能受限）

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
}

externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}

dependencies {
    // 压缩包解压（imagefs.txz = XZ；容器/组件包 = ZSTD），与原版一致
    api("com.github.luben:zstd-jni:1.5.2-3")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    // WinHandler/外部手柄的偏好读取
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("androidx.collection:collection:1.2.0")
}
