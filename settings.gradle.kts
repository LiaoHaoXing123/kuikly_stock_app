pluginManagement {
    repositories {

        maven("https://mirrors.tencent.com/repository/maven-tencent/")

        google()
        gradlePluginPortal()
        mavenCentral()

        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")

        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {

        maven("https://mirrors.tencent.com/repository/maven-tencent/")

        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")

        google()
        mavenCentral()
        gradlePluginPortal()

        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")

        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenLocal()

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
