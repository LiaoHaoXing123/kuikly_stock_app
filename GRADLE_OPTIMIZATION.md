# Gradle 同步踩坑与最终配置（kuikly_stock_app2）

> 最后更新：2026-08-18 22:04
> 状态：**✅ BUILD SUCCESSFUL（Sync 已通过）**，耗时 27m 4s

本文记录本仓库从「Gradle 同步一路报错」到「最终 Sync 通过」的完整踩坑过程。
所有镜像/仓库地址都经过 **实际 curl 请求验证**，不是凭印象配置。

---

## 一、最终生效的配置（可直接照搬）

### 1. Gradle 发行版下载源
**文件**：`gradle/wrapper/gradle-wrapper.properties`
```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip
```
> ⚠️ 阿里云的 `maven/gradle/...` 路径 **404 不可用**，最终用腾讯云 `mirrors.cloud.tencent.com/gradle/` （已验证 200 OK）。

### 2. Maven 依赖仓库
**文件**：`settings.gradle.kts`

#### pluginManagement（插件仓库）顺序
```
1. maven("https://mirrors.tencent.com/repository/maven-tencent/")  ← Kuikly 私有仓（必须最前！）
2. google()
3. gradlePluginPortal()
4. mavenCentral()
5. maven("https://mirrors.tencent.com/nexus/repository/maven-public/")  ← 腾讯云公共镜像（兜底）
6. maven("https://maven.aliyun.com/repository/google")                  ← 阿里云（兜底）
7. maven("https://maven.aliyun.com/repository/public")
8. maven("https://maven.aliyun.com/repository/gradle-plugin")
9. mavenLocal()
```

#### dependencyResolutionManagement（依赖仓库）顺序
```
1. maven("https://mirrors.tencent.com/repository/maven-tencent/")  ← Kuikly 私有仓（必须最前！）
2. google()
3. mavenCentral()
4. gradlePluginPortal()
5. maven("https://mirrors.tencent.com/nexus/repository/maven-public/")  ← 腾讯云公共镜像（兜底）
6. maven("https://maven.aliyun.com/repository/google")                  ← 阿里云（兜底）
7. maven("https://maven.aliyun.com/repository/public")
8. mavenLocal()
9. ivy("https://services.gradle.org/distributions/")  ← 仅 gradle 组，修复 Gradle 源码转换失败
```

> ⚠️ **仓库顺序原则**：`Kuikly 私有仓(maven-tencent) 最前 → 官方源(google/mavenCentral/gradlePluginPortal) → 国内镜像(腾讯云公共/阿里云)兜底`。
> 国内公共镜像对 KMP 的 native 依赖 / Kuikly 私有包**不完整**，绝不能排在官方源前面。

### 3. 最终 Sync 结果（2026-08-18 实测）
```
BUILD SUCCESSFUL in 27m 4s
11 actionable tasks: 1 executed, 10 up-to-date
```
- ✅ Kuikly 私有依赖（core / core-ksp / core-annotations / core-render-android 等）正常解析
- ✅ Ktor 的 iOS native 变体（`-all.jar`）从 Maven Central 正常解析
- ✅ `gradle-8.5-src.zip` 从 `services.gradle.org` 下载成功（慢，约 21 分钟，但成功）
- ⚠️ iOS 目标（iosArm64 / iosSimulatorArm64 / iosX64）在 **Windows** 上被自动禁用（需 Mac + Xcode 才能编译，不影响 Android 构建）

---

## 二、踩坑记录（本次对话 4 个问题）

### 坑 1：阿里云 Gradle 镜像 404
**现象**：Sync 时 Gradle 发行版下载失败 / 报 404。
**根因**：阿里云镜像没有 `maven/gradle/gradle-8.5-bin.zip` 这个路径（已 curl 验证 404）。
**修复**：改用腾讯云 `mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip`（已验证 200 OK）。
**验证**：`curl -I https://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip` → 200。

---

### 坑 2：Kuikly `core-gradle-plugin` 找不到 + `maven.kuikly.com` 域名失效
**现象**：
```
Could not find com.tencent.kuikly-open:core-gradle-plugin:2.7.0-2.1.21
Unknown host 'maven.kuikly.com'
```
**根因（两个独立错误叠加）**：
1. `maven.kuikly.com` 域名**已失效**（DNS 全球解析不了，Non-existent domain）—— 网上搜到的旧方案给的地址是死的。
2. 之前用的 `mirrors.tencent.com/nexus/repository/maven-public/` 路径**不含** kuikly-open 私有包（404）。

**正确答案（来自腾讯官方 `Tencent-TDS/KuiklyUI` 仓库的 `settings.2.1.21.gradle.kts`）**：
```kotlin
maven("https://mirrors.tencent.com/repository/maven-tencent/")
```
注意路径是 `mirrors.tencent.com/repository/maven-tencent/`（**没有 `/nexus/` 前缀**，且是 `maven-tencent` 而非 `maven-public`）。

**已验证该仓库包含全部依赖**（HTTP 200）：
- `core-gradle-plugin:2.7.0-2.1.21`（classpath 插件，走 pluginManagement + buildscript.repositories）
- `core`、`core-annotations`（**注意是复数**）、`core-render-android`、`core-ksp` 的 `2.7.0-2.1.21`

**修复文件**：`settings.gradle.kts`（两处 repositories 块最前加官方仓库）、`build.gradle.kts`（buildscript 块补 repositories）。

> 🔑 **关键结论**：Kuikly 私有依赖唯一可用的国内源就是 `https://mirrors.tencent.com/repository/maven-tencent/`，**千万别再用 `maven.kuikly.com`**。

---

### 坑 3：Ktor iOS native `-all.jar` 找不到
**现象**：
```
Could not resolve :shared:iosMainResolvableDependenciesMetadata
Could not find ktor-serialization-kotlinx-2.3.0-all.jar (io.ktor:ktor-serialization-kotlinx:2.3.0)
Searched in: mirrors.tencent.com/nexus/repository/maven-public/...
```
**根因**：
- `-all.jar` 是 Kotlin/Native 的元数据聚合格式，KMP 库（如 Ktor）发布在 **Maven Central**。
- 腾讯云 `nexus/repository/maven-public/` **未同步 Ktor 的 native 变体**（实测该目录下根本没有 `io.ktor` 内容，只有 Nexus 浏览页）。
- 它却「部分拥有」`io.ktor` 组的 pom，导致 Gradle 锁定到该残缺仓库、**不再回退到 Maven Central**。

**修复**：把 `google()` / `mavenCentral()` / `gradlePluginPortal()` 移到腾讯云公共镜像**之前**（maven-tencent 仍最前）。
- 验证 `io.ktor:ktor-client-core` / `ktor-client-content-negotiation` / `ktor-serialization-kotlinx(-json):2.3.0` 的 `-all.jar` 在 Maven Central 均 HTTP 200。

> ⚠️ **关键结论**：腾讯云公共镜像 `maven-public` 对 Kotlin Multiplatform 的 native 依赖**不完整**，不能排在官方源前面。

---

### 坑 4：Gradle 源码 `src-directory` 转换失败
**现象**：
```
org.gradle.api.internal.artifacts.transform.TransformException:
Failed to transform gradle-8.5-src.zip (gradle:gradle:8.5)
to match attributes {artifactType=src-directory, org.gradle.status=integration}
```
**根因**：
- `gradle:gradle:8.5` **不是标准 Maven 构件**：Maven Central / 腾讯云 maven-public / 阿里云 public / Gradle Plugin Portal 全部 404（Plugin Portal 仅把请求 303 重定向到 Maven Central 的 404）。
- `gradle-8.5-src.zip` 只存在于 Gradle 官方分发服务器 `https://services.gradle.org/distributions/gradle-8.5-src.zip`（307 → GitHub Releases），且**非 Maven 格式**。
- 项目里没有任何地方声明 `gradle:gradle` 依赖 —— 这是 **Android Studio / Kotlin IDE 插件在 Sync 时为「Gradle 内部 API」请求源码导航**自动触发的，与业务代码无关。

**修复（方案 B，已采用）**：在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 末尾加一个**只作用于 `gradle` 组**的 Ivy 源：
```kotlin
ivy("https://services.gradle.org/distributions/") {
    patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
    metadataSources { artifact() }
    content { includeGroup("gradle") }   // 仅作用于 gradle 组，不影响其他依赖
}
```
**验证**：最终 Sync 日志显示 `Download https://services.gradle.org/distributions/gradle-8.5-src.zip` 成功（耗时约 21 分钟，慢但成功）。

> 💡 **备选方案 A（不依赖网络 / 更快）**：若上面的 Ivy 源因网络屏蔽 GitHub 失败，可在 Android Studio 里
> **Settings → Build, Execution, Deployment → Gradle → 取消勾选 "Download sources for dependencies"**，
> 再重新 Sync。原理是直接不请求源码工件，彻底绕开转换。代价只是不能 Ctrl+Click 进 Gradle 内部源码，**对编译运行零影响**。

---

## 三、操作指南

### 1. 修改配置后重新 Sync
Android Studio 顶部 **File → Sync Project with Gradle Files**（或点工具栏 🐘 图标）。

### 2. 怀疑缓存损坏时清理
早期坏镜像可能把 404 HTML 缓存成 zip，导致转换失败。可清理：
```bash
# Windows
rd /s /q "%USERPROFILE%\.gradle\caches\transforms-*"
rd /s /q "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\gradle\"
```
清理后即重新 Sync。

### 3. 验证清单
- [x] `gradle-wrapper.properties` 用腾讯云 Gradle 镜像（`mirrors.cloud.tencent.com/gradle/`）
- [x] `settings.gradle.kts` 两处 repositories 最前为 `maven-tencent`
- [x] 官方源（google/mavenCentral/gradlePluginPortal）在腾讯云公共镜像之前
- [x] `dependencyResolutionManagement` 含 `ivy("https://services.gradle.org/distributions/")` 且 `includeGroup("gradle")`
- [x] 重新 Sync 后 `BUILD SUCCESSFUL`

---

## 四、注意事项

1. **Windows 无法编译 iOS**：`shared` 模块启用了 iOS 目标，Sync 会解析其元数据依赖（需能从 Maven Central 拿 Ktor native）；实际编译 iOS 需 Mac + Xcode。当前 Sync 通过、Android 可正常构建运行。
2. **`gradle-8.5-src.zip` 下载慢**：官方分发服务器跳转 GitHub Releases，国内网络慢（实测约 21 分钟）。若想跳过，用「备选方案 A」关掉源码下载。
3. **不要信任网上搜到的旧 Kuikly 镜像地址**：`maven.kuikly.com` 已死；以 `Tencent-TDS/KuiklyUI` 仓库的 `settings.<kotlin版本>.gradle.kts` 为准。
4. **改动仓库顺序后务必重新 Sync**，否则 Gradle 仍用旧解析结果。

---

## 五、总结

本次 Sync 一共踩了 4 个坑，根因各不相同：
1. 阿里云 Gradle 镜像路径 404 → 换腾讯云
2. Kuikly 私有仓地址错误（`maven.kuikly.com` 失效）→ 用 `maven-tencent`
3. 国内公共镜像对 KMP native 依赖不完整且会「锁死」不回退 → 官方源排前面
4. Gradle 源码不在 Maven 仓库 → 用 Ivy 源指向官方分发服务器（或关掉 IDE 源码下载）

按本文「最终生效的配置」照搬即可一次 Sync 通过。
