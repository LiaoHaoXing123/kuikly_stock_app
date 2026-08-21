plugins {
    //trick: for the same plugin versions in all sub-modules
    id("com.android.application").version("7.4.2").apply(false)
    id("com.android.library").version("7.4.2").apply(false)
    kotlin("android").version("2.1.21").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
    kotlin("plugin.serialization").version("2.1.21").apply(false)
    id("com.google.devtools.ksp").version("2.1.21-2.0.1").apply(false)

}

buildscript {
    repositories {
        // 🔑 Kuikly官方私有仓库（classpath 依赖来源，必须包含）
        maven("https://mirrors.tencent.com/repository/maven-tencent/")
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
    }
    dependencies {
        classpath(BuildPlugin.kuikly)
        // 覆盖 R8：AGP 7.4.2 自带的 R8 太旧，无法解析 Kotlin 2.1 元数据，
        // 导致 :androidApp:mergeExtDexDebug 报 com.android.tools.r8.kotlin.H。
        // R8 8.3.37 起支持 Kotlin 2.1 元数据，且离 AGP 7.4.2 自带版本最近，兼容性最佳。
        classpath("com.android.tools:r8:8.3.37")
    }
}