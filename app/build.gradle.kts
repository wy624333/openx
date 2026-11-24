plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ 的 Compose 编译器插件（版本随根工程 Kotlin 2.0.21）
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wy.openx"
    // Sora Editor 0.24.6 强制要求 compileSdk 36（AAR metadata 校验会直接失败）。
    compileSdk = 36
    // 必须用 35.0.0 的 build-tools：34.0.0 的 aidl 在本机（ARM64）会以 Illegal instruction 崩溃，
    // Shizuku 的 IShizukuShellService.aidl 无法编译；35.0.0 正常（参考项目同用 35.0.0）。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.wy.openx"
        minSdk = 24
        // 锁定 targetSdk 28：Android 10+（API 29+）的 W^X/SELinux 策略禁止执行 App 可写
        // 数据目录里的文件，PRoot 容器二进制将无法运行（同 Termux/AiCode 的取舍）。
        // 代价：不能上 Google Play，走内部分发。
        targetSdk = 28
        versionCode = 105
        versionName = "2.10.2"
        buildConfigField("Long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters.add("arm64-v8a") }
    }

    // signingConfigs 必须在 buildTypes 之前：KTS 按顺序求值，release 引用才找得到。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("签名密钥/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // release 复用同一把钥匙：侧载不上架，保持与 debug 同签名才能覆盖安装升级。
        create("release") {
            storeFile = rootProject.file("签名密钥/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 关键：debug 签名改成老 APK 同款密钥（原构建设备的 debug.keystore），
    // 保证 adb install -r 覆盖安装不因签名不一致而失败。
    // release 的 signingConfig 已在上方（buildTypes 之前）声明，这里不再重复：
    // KTS 按顺序求值，这个位置的 create("release") 会让 buildTypes 里的 getByName 找不到。

    lint {
        // targetSdk 28 是刻意的：Android 10+ 的 W^X/SELinux 禁止执行 App 可写目录里的二进制，
        // proot 容器依赖这个降级才能跑。ExpiredTargetSdkVersion 是上架 Google Play 的要求，
        // 与本项目（侧载、不上架）无关，不关掉会让 assembleRelease 末尾恒定失败。
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests {
            // 单测跑在 JVM 上，android.jar 里的方法是空壳；不开这个，
            // 任何间接调到 android.util.Log 的代码（如 FileLogger）都会抛
            // 「Method i in android.util.Log not mocked」，让被测逻辑根本没机会执行。
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // language-textmate 在 API 33 以下依赖 java.time 等核心库脱糖
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            // Sora Editor 0.24.6 用 Kotlin 2.3 编译（metadata 2.3.0），本工程编译器为 2.0.21。
            // 其 API 未使用 2.0 之后的新语言特性，跳过元数据版本校验即可正常调用；
            // 这样避免为引入一个编辑器而整体升级 Kotlin/Compose/KSP 工具链。
            "-Xskip-metadata-version-check"
        )
    }
    // aidl：Shizuku UserService 的 IShizukuShellService 接口
    buildFeatures { compose = true; buildConfig = true; aidl = true }

    // BouncyCastle（bcprov/bcpkix/bcutil）与 jspecify 各自带一份
    // META-INF/versions/9/OSGI-INF/MANIFEST.MF，多份同名资源会让 mergeJavaResource 直接失败。
    // 这些是 OSGi 模块描述，Android 运行时用不到；其余 META-INF 签名/版本文件同样排除。
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**/OSGI-INF/**"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/INDEX.LIST"
            // Apache 系列 jar（ftpserver / mina / ftplet-api）各带一份依赖清单与许可文件，
            // 三份同名会让 mergeJavaResource 直接失败；这些只是构建期元数据，运行时用不到。
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/DEPENDENCIES.txt"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
            // sshj 的默认配置随 jar 带一份，注释里说明靠代码里的显式配置，不依赖它
            excludes += "sshj.properties"
        }
    }
    // Kotlin 2.0+ 起，Compose 编译器由 org.jetbrains.kotlin.plugin.compose 插件提供，
    // 不再需要 composeOptions.kotlinCompilerExtensionVersion（旧的 1.5.14 仅支持 Kotlin 1.9.24）
}

dependencies {
    implementation(project(":quickjs"))

    // Shizuku：以 adb shell（uid 2000）身份执行系统命令。api 提供 Shizuku 类与 UserService 绑定，
    // provider 注册 ShizukuProvider（见 AndroidManifest）以跨进程获取 binder。
    // 依赖已内置到 app/libs/shizuku-repo，离线可构建。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3-android:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 音频播放（插件 ctx.audio / audiobar 组件）。
    // 用 media3 而不是系统 MediaPlayer：radio-browser 里大量中文电台是 HLS(m3u8)，
    // MediaPlayer 放不了；-hls 模块补上 HLS，session 提供媒体通知与耳机键控制。
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // 用 OkHttp 做数据源：电台站大量用 301/302 跳转（默认 HTTP 源不跟随），
    // 而且可以单独给直播流配更长的读超时。
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // 远程工作区（设置 → 连接与同步）：SFTP 走 sshj，FTP 走 commons-net。
    // sshj 0.38.0 用 X25519 做密钥交换，而 Android 自带的裁剪版 BouncyCastle 不含该算法，
    // 不显式引入 BC 会在握手阶段报「no such algorithm」。1.78.1 是为了避开
    // 1.75 的 CVE-2024-30172（Ed25519 验证死循环 DoS）。
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("commons-net:commons-net:3.10.0")

    // 内置 FTP 服务器（设置 → 连接与同步 → 内置FTP）：把手机存储挂出去给电脑访问。
    // 传递依赖 apache mina-core；slf4j 已由工程其他依赖引入。
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 容器：解压 Alpine rootfs tar.gz（正确处理 symlink/hardlink/权限位）
    implementation("org.apache.commons:commons-compress:1.26.2")
    // xz 解压支持：commons-compress 的 XZCompressorInputStream 依赖此库（解压用户导入的 .tar.xz 镜像）
    implementation("org.tukaani:xz:1.10")
    // SKILL.md frontmatter（YAML）解析：技能以目录 + 指令文件形式存在 .openx/skills/<name>/
    implementation("org.yaml:snakeyaml:2.2")

    // 代码/文档编辑器：Sora Editor（原生 CodeEditor View）。
    // 取代原先的 OutlinedTextField + 每行独立 SelectionContainer 源码视图，
    // 由原生控件提供跨行选择/换行复制。
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    // Room 迁移里那几段回填 SQL 的语义回归（SeqMigrationTest）：用真正的 SQLite 引擎跑一遍，
    // 而不是靠肉眼审 SQL。踩过的坑：`UPDATE ... SET seq = (SELECT ... WHERE ... seq ...)`
    // 子查询引用了正在被逐行改写的列 → 所有 seq 都算成 1，显示序静默失效。
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}