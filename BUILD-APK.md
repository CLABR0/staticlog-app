# APK 构建说明

这是 Android 应用（applicationId：`com.demo.zt`）的 APK 构建源码包，不含已构建的 APK 产物；解压后以 `ztui/` 为工程根目录。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | **JDK 21**；将 `JAVA_HOME` 指向 JDK 21，并确保命令行使用该版本。Java/Kotlin 的目标均为 JVM 21，JDK 17 会构建失败。 |
| Android SDK 组件 | 必装 **`platforms;android-37.0`** 和 **`build-tools;37.0.0`**。可通过 Android Studio 的 SDK Manager 或 SDK 命令行工具安装；设备测试另需可用设备或模拟器。 |
| Gradle | 使用包内 Gradle Wrapper，已配置 **`gradle-9.3.1-bin`**，无需另装系统 Gradle；首次使用需要下载对应分发包。 |
| 网络 | 首次构建需联网下载依赖，包括 Miuix 0.9.4、Shizuku、Conscrypt 等；`settings.gradle.kts` 中的仓库配置已就绪。 |

构建配置来自 `app/build.gradle.kts`：

| 配置 | 值 |
| --- | --- |
| plugins | `com.android.application`、`org.jetbrains.kotlin.plugin.compose` |
| namespace / applicationId | `com.demo.zt` |
| compileSdk | `37` |
| buildToolsVersion | `"37.0.0"` |
| minSdk / targetSdk | `26` / `36` |
| Java / Kotlin target | `compileOptions` 与 Kotlin `jvmTarget` 都是 `21` |
| versionCode / versionName | 从 `rootProject/version.properties` 读取 |
| release | `isMinifyEnabled=false`，不走混淆；使用 `.android/sl-release.jks` 签名 |

关键依赖版本：

| 依赖 | 版本 |
| --- | --- |
| `androidx.activity:activity-compose` | `1.13.0` |
| `androidx.core:core-ktx` | `1.13.1` |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.8.7` |
| Compose `ui` / `foundation` | `1.10.5` |
| Compose `material3` | `1.4.0` |
| Compose `material-icons-extended` | `1.7.8` |
| `top.yukonga.miuix.kmp:{miuix-ui,miuix-nav,miuix-preference,miuix-icons}` | `0.9.4` |
| `dev.rikka.shizuku:{api,provider}` | `13.1.5` |
| `org.conscrypt:conscrypt-android` | `2.5.2` |
| `org.bouncycastle:{bcprov-jdk18on,bcpkix-jdk18on}` | `1.78.1` |
| `com.github.topjohnwu.libsu:core` | `6.0.0` |

## 三步构建 release APK

以下命令均从解压后的位置开始执行；进入 `ztui/` 后，后续构建、测试命令都在该目录运行。

### 第一步：配置本机 SDK 路径

**Windows（PowerShell）：**

```powershell
cd .\ztui
Copy-Item .\local.properties.example .\local.properties
notepad .\local.properties
```

**Linux / macOS：**

```bash
cd ztui
cp local.properties.example local.properties
${EDITOR:-vi} local.properties
```

将 `local.properties` 中的 `sdk.dir` 改为**本机 Android SDK 的实际目录**。下面只是路径格式示例，按机器实际位置选择并修改，不要同时写入多条：

Windows 示例（使用正斜杠可避免反斜杠转义）：

```properties
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk
```

Linux 示例：

```properties
sdk.dir=/home/yourname/Android/Sdk
```

macOS 示例：

```properties
sdk.dir=/Users/yourname/Library/Android/sdk
```

**不要跨机器复制 `local.properties`**；包内提供的是 `local.properties.example`，只含 `sdk.dir` 示例。

### 第二步：执行构建

确认 JDK 为 21，然后生成带 release 签名的 APK。

**Windows（PowerShell）：**

```powershell
java -version
.\gradlew.bat :app:assembleRelease
```

**Linux / macOS：**

```bash
java -version
chmod +x gradlew
./gradlew :app:assembleRelease
```

首次构建需要下载 Gradle 和依赖，等待任务成功结束；不需要单独运行系统安装的 Gradle。

### 第三步：取得 APK

相对工程根目录的标准 release 产物位置：

```text
app/build/outputs/apk/release/app-release.apk
```

即解压位置下的：

```text
ztui/app/build/outputs/apk/release/app-release.apk
```

若工程另有输出文件重命名逻辑，以 `app/build/outputs/apk/release/` 中实际生成的 APK 文件名为准。

## 签名说明

**这个包里没有 keystore。** 私钥不随源码分发（`.gitignore` 里已经排掉了 `.android/`）。

`app/build.gradle.kts` 的 release 签名配置指向：

| 项目 | 值 |
| --- | --- |
| release keystore（相对工程根目录） | `.android/sl-release.jks` |
| storePass | `staticlog-release` |
| keyAlias | `staticlog` |
| keyPass | `staticlog-release` |
| debug keystore | `.android/debug.keystore` |

签名文件缺失时 release 构建会直接失败。自己生成一份：

```bash
mkdir -p .android
keytool -genkeypair -v \
  -keystore .android/sl-release.jks \
  -alias staticlog \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass staticlog-release -keypass staticlog-release \
  -dname "CN=StaticLog, OU=Dev, O=Local, L=NA, S=NA, C=CN"
```

debug 签名不需要准备，AGP 会自动生成到 `~/.android/debug.keystore`；若工程里缺 `.android/debug.keystore`，删掉 `app/build.gradle.kts` 中 `signingConfigs.getByName("debug")` 那三行即可。

**换成自己的密钥**：改 `app/build.gradle.kts` 里 `signingConfigs.create("release")` 的四个字段。密码建议不要硬编码在构建脚本里，改成读 `gradle.properties`（该文件已在 `.gitignore` 中）或 CI 的环境变量：

```kotlin
signingConfigs.create("release") {
    storeFile = rootProject.file(".android/sl-release.jks")
    storePassword = (project.findProperty("SL_STORE_PASS") as String?)
    keyAlias = (project.findProperty("SL_KEY_ALIAS") as String?) ?: "staticlog"
    keyPassword = (project.findProperty("SL_KEY_PASS") as String?)
}
```

```properties
# gradle.properties（本机私有，不要提交）
SL_STORE_PASS=你自己的密码
SL_KEY_PASS=你自己的密码
```

更换私钥会改变签名身份：已安装的旧签名应用通常无法直接被新签名 APK 覆盖更新。本地测试可卸载旧版再安装，但卸载会清除应用数据。

## 修改版本号

编辑工程根目录的 `version.properties`，修改其中供 `versionCode`、`versionName` 读取的现有属性值，保留文件原有键名和格式，然后重新构建。

- `versionCode`：整数；发布更新时递增。
- `versionName`：对外展示的版本字符串。
- 两者由构建脚本从 `rootProject/version.properties` 读取，不要只修改界面上的版本文本。

## 可选参数：统计上报地址

构建参数 `-Pstaticlog.statsEndpoint=https://example.com` 会注入 `BuildConfig.STATS_ENDPOINT`。地址留空或不传该参数时，**上报功能整个关闭**。下面的地址是示例，启用时替换为实际接收端点。

**Windows（PowerShell）：**

```powershell
.\gradlew.bat :app:assembleRelease -Pstaticlog.statsEndpoint=https://example.com
```

**Linux / macOS：**

```bash
./gradlew :app:assembleRelease -Pstaticlog.statsEndpoint=https://example.com
```

此值是构建时配置，修改后需重新构建 APK。

## 运行测试

### JVM 单元测试

源码包含 **35 个 JVM 单测文件，274 条用例**。必须明确运行 `:app:testDebugUnitTest`，不要以 `test` 聚合任务成功作为这些单测全部执行的证据。

**Windows（PowerShell）：**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

**Linux / macOS：**

```bash
./gradlew :app:testDebugUnitTest
```

测试报告入口：

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

检查报告中的执行数量、失败与跳过情况；上述文件数和用例数是源码背景信息，不代表本文已经运行测试。

测试依赖中保留以下配置：

```kotlin
testImplementation("org.json:json:20240303")
```

原因：`android.jar` 中的 `org.json` 在 JVM 单测中是 stub，会返回 `null`；JVM 单测需要真实的 JSON 实现。

### 设备测试

源码还包含 **7 个设备测试文件**。连接可用的 Android 设备或启动模拟器（API 26 及以上），再执行：

**Windows（PowerShell）：**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

**Linux / macOS：**

```bash
./gradlew :app:connectedDebugAndroidTest
```

## 常见错误对照表

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| JVM target 21 相关报错，或提示不支持 Java release 21 | 构建实际使用 JDK 17 等不满足要求的版本 | 安装并切换到 JDK 21；校正 `JAVA_HOME` 和 PATH。若从 Android Studio 构建，同时将 Gradle JDK 设为 21。 |
| SDK location not found，或 SDK 路径指向其他机器 | 未生成 `local.properties`，或直接复制了他人的本机路径 | 从 `local.properties.example` 复制生成 `local.properties`，将 `sdk.dir` 改为当前机器实际 SDK 路径。 |
| `Failed to find target with hash string 'android-37.0'`，或缺少 Build Tools 37.0.0 | SDK 缺少指定平台或构建工具 | 在当前 `sdk.dir` 指向的 SDK 中安装 `platforms;android-37.0` 与 `build-tools;37.0.0`；不要为了消除报错随意改低工程版本。 |
| 首次构建无法解析依赖、下载超时，Miuix / Shizuku / Conscrypt 等下载失败 | 首次下载需要联网，或当前网络无法访问已配置的仓库 | 检查网络、代理和仓库连通性后重试；仓库配置已在 `settings.gradle.kts` 中就绪。离线模式不能替代首次依赖下载。 |
| 运行 `test` 后显示成功，但没有实际单测执行记录 | AGP 9 下 `test` 是聚合任务，容易误报全绿 | 使用 `:app:testDebugUnitTest`，打开对应 HTML 报告核对执行数量和结果。 |
| Windows PowerShell 显示中文为 `��` | 控制台编码问题，不是 UTF-8 文件损坏 | 使用支持 UTF-8 的终端或编辑器，读取时指定 UTF-8；不要按乱码重新保存或覆盖原文件。 |
| JVM 单测中 JSON 调用返回 `null` | 使用了 `android.jar` 的 `org.json` stub | 保留 `testImplementation("org.json:json:20240303")`，确认运行的是 JVM 单测任务。 |
| Linux / macOS 执行 wrapper 时提示 Permission denied | 解压后 `gradlew` 缺少执行权限 | 为 `gradlew` 添加执行权限，再运行 wrapper；见第二步的 Linux / macOS 命令。 |

PowerShell 中可显式按 UTF-8 读取本文以确认内容：

```powershell
Get-Content -LiteralPath 'BUILD-APK.md' -Encoding UTF8
```

该命令应在本文所在目录执行；若本文未放在当前目录，请使用实际文件路径。

## 目录结构（简明版）

```text
ztui/
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties      # gradle-9.3.1-bin
├── build.gradle.kts
├── settings.gradle.kts                # 仓库配置
├── gradle.properties
├── version.properties                 # 版本号来源
├── .gitignore
├── local.properties.example           # 仅 sdk.dir 示例；复制并改成本机路径
├── .android/                           # 包内为空目录，放你自己生成的 keystore
│   └── (keystore 不在包内，需自行生成)
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/                     # 133 个文件，22.3MB
        │   └── assets/variants/
        │       ├── 6-12g/
        │       ├── 6-16g/
        │       ├── 7-12g/
        │       ├── 7-16g/
        │       └── probe/
        ├── test/                     # 35 个 JVM 单测文件，274 条用例
        └── androidTest/              # 7 个设备测试文件
```
