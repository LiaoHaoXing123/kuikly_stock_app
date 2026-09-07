import com.tencent.kuikly.gradle.config.KuiklyConfig

plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("com.tencent.kuikly-open.kuikly")

}

val KEY_PAGE_NAME = "pageName"

// ---- 构建期安全注入：从项目根 .env 读取 AI 密钥并生成源码，避免密钥硬编码进源码/入库 ----
// 说明：.env 已 gitignore 不入库；生成文件写入 build/generated，不参与版本控制。
// 这只是"密钥不入库/不进源码"，并不能防止 APK 被反编译——正式分发仍建议走自建后端代理。
fun dshReadEnv(name: String, default: String = ""): String {
    val f = rootProject.file(".env")
    if (!f.exists()) return default
    return f.readLines()
        .map { it.trim() }
        .firstOrNull {
            it.isNotEmpty() && !it.startsWith("#") && '=' in it &&
                it.substring(0, it.indexOf('=')).trim() == name
        }
        ?.let { it.substring(it.indexOf('=') + 1).trim() }
        ?: default
}

fun dshEscapeKotlinString(raw: String): String =
    raw.replace("\\", "\\\\").replace("\"", "\\\"")

val aiSecretsDir = layout.buildDirectory.dir("generated/aiSecrets")
val generateAiSecrets = tasks.register("generateAiSecrets") {
    val apiKey = dshReadEnv("DEEPSEEK_API_KEY")
    val baseUrl = dshReadEnv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")
    val model = dshReadEnv("DEEPSEEK_MODEL", "deepseek-v4-flash")
    inputs.property("apiKey", apiKey)
    inputs.property("baseUrl", baseUrl)
    inputs.property("model", model)
    outputs.dir(aiSecretsDir)
    doLast {
        val out = aiSecretsDir.get().asFile
        val f = out.resolve("com/kuikly/stock/ai/config/AiSecretsGenerated.kt")
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package com.kuikly.stock.ai.config
            |
            |// 自动生成：由 shared/build.gradle.kts 从 .env 注入，勿手动编辑（.env 不入库）。
            |internal object AiSecretsGenerated {
            |    const val API_KEY: String = "${dshEscapeKotlinString(apiKey)}"
            |    const val BASE_URL: String = "${dshEscapeKotlinString(baseUrl)}"
            |    const val MODEL: String = "${dshEscapeKotlinString(model)}"
            |}
            """.trimMargin() + "\n"
        )
    }
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
        publishLibraryVariants("release")
    }

    js(IR) {
        browser {
            webpackTask {
                outputFileName = "nativevue2.js" // 最后输出的名字
            }

            commonWebpackConfig {
                output?.library = null // 不导出全局对象，只导出必要的入口函数
                devtool = "source-map" // 不使用默认的 eval 执行方式构建出 source-map，而是构建单独的 sourceMap 文件
            }
        }
        binaries.executable() //将kotlin.js与kotlin代码打包成一份可直接运行的js文件
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "14.1"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "shared"
            freeCompilerArgs = freeCompilerArgs + getCommonCompilerArgs()
            isStatic = true
            license = "MIT"
        }
        extraSpecAttributes["resources"] = "['src/commonMain/assets/**']"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:2.3.0")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
                // 日志插件（跨平台，含 JS）
                implementation("io.ktor:ktor-client-logging:2.3.0")
                // kotlinx-serialization 运行时（Kotlin 2.1 配套版本；依赖 serialization 编译插件生成代码）
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyVersion()}")

            }
            // 加入构建期由 .env 生成的密钥源码目录
            kotlin.srcDir(generateAiSecrets.map { aiSecretsDir.get().asFile })
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                api("com.tencent.kuikly-open:core-render-android:${Version.getKuiklyVersion()}")
                // Android 上使用 OkHttp 引擎，比 CIO 在真机网络环境下更稳定
                implementation("io.ktor:ktor-client-okhttp:2.3.0")
                // 提供 Dispatchers.Main：协程里更新 Kuikly 响应式状态必须在主线程
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
            }
        }

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                // iOS/Native 引擎
                implementation("io.ktor:ktor-client-darwin:2.3.0")
            }
        }
        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
            iosX64Test.dependsOn(this)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }

        // JS 目标（h5App / miniApp 使用）
        val jsMain by getting {
            dependencies {
                // ⚠️ CIO 不支持 JS，JS 平台必须用 ktor-client-js
                implementation("io.ktor:ktor-client-js:2.3.0")
            }
        }
    }
}

group = "com.kuikly.stock"
version = System.getenv("kuiklyBizVersion") ?: "1.0.0"

publishing {
    repositories {
        maven {
            credentials {
                username = System.getenv("mavenUserName") ?: ""
                password = System.getenv("mavenPassword") ?: ""
            }
            rootProject.properties["mavenUr?"]?.toString()?.let { url = uri(it) }
        }
    }
}

ksp {
    arg(KEY_PAGE_NAME, getPageName())
}

dependencies {
    compileOnly("com.tencent.kuikly-open:core-ksp:${Version.getKuiklyVersion()}") {
        add("kspAndroid", this)
        add("kspIosArm64", this)
        add("kspIosX64", this)
        add("kspIosSimulatorArm64", this)
        add("kspJs", this)
    }
}

android {
    namespace = "com.kuikly.stock.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        targetSdk = 30
    }
    sourceSets {
        named("main") {
            assets.srcDirs("src/commonMain/assets")
        }
    }
    // stock.db 必须未压缩存储，否则 Android AssetManager.openFd() 拿不到文件描述符
    // （压缩资产 openFd 抛异常），导致 initStockDb 拷贝失败。App 离线查库依赖此配置。
    androidResources {
        noCompress += "db"
    }
}

fun getPageName(): String {
    return (project.properties[KEY_PAGE_NAME] as? String) ?: ""
}

fun getCommonCompilerArgs(): List<String> {
    return listOf(
        "-Xallocator=std"
    )
}

fun getLinkerArgs(): List<String> {
    return listOf()
}

// Kuikly 插件配置
configure<KuiklyConfig> {
    // JS 产物配置
    js {
        // 构建产物名，与 KMM 插件 webpackTask#outputFileName 一致
        outputName("nativevue2")
        // 可选：分包构建时的页面列表，如果为空则构建全部页面
        // addSplitPage("route","home")
    }
}