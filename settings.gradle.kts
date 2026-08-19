pluginManagement {
    repositories {
        // 🔑 Kuikly官方私有仓库（必须最前！）
        maven("https://mirrors.tencent.com/repository/maven-tencent/")
        // ✅ 官方源（第三方库标准变体，含Ktor Native，必须在腾讯云公共镜像之前）
        google()
        gradlePluginPortal()
        mavenCentral()
        // ✅ 国内镜像加速（放后面兜底，腾讯云公共镜像不完整会回退到上面）
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        // 阿里云镜像（备用源）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 🔑 Kuikly官方私有仓库（必须最前！）
        maven("https://mirrors.tencent.com/repository/maven-tencent/")
        // ✅ 官方源（第三方库标准变体，含Ktor Native，必须在腾讯云公共镜像之前）
        google()
        mavenCentral()
        gradlePluginPortal()
        // ✅ 国内镜像加速（放后面兜底）
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        // 阿里云镜像（备用源）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenLocal()
        // 🛠️ Gradle 官方分发源：修复 IDE 请求 gradle:gradle:8.5 源码(src-directory)转换失败
        // 该工件不在任何 Maven 仓库，只在 services.gradle.org（跳转 GitHub Releases）
        // 仅作用于 gradle 组，不影响其他依赖
        ivy("https://services.gradle.org/distributions/") {
            patternLayout {
                artifact("[artifact]-[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeGroup("gradle") }
        }
    }
}

rootProject.name = "kuikly_stock_app2"
include(":androidApp")
include(":shared")
include(":h5App")
include(":miniApp")