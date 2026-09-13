plugins {

    id("com.android.application").version("7.4.2").apply(false)
    id("com.android.library").version("7.4.2").apply(false)
    kotlin("android").version("2.1.21").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
    kotlin("plugin.serialization").version("2.1.21").apply(false)
    id("com.google.devtools.ksp").version("2.1.21-2.0.1").apply(false)

}

buildscript {
    repositories {

        maven("https://mirrors.tencent.com/repository/maven-tencent/")
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
    }
    dependencies {
        classpath(BuildPlugin.kuikly)

        // 支持 Kotlin 2.1 元数据，避免旧版 R8 在 DEX 阶段失败。
        classpath("com.android.tools:r8:8.3.37")
    }
}
