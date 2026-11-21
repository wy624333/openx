plugins {
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Compose 编译器在 Kotlin 2.0+ 改为独立 Gradle 插件（与 Kotlin 版本严格一致），
    // 不再用旧版 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}