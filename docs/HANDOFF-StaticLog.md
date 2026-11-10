# 交接文档 · StaticLog（安卓端集成工具）

生成时间：2026-11-03 12:05
工程目录：`ztui/`
工作区根：`C:\Users\Administrator\Documents\deepseek-harness\default-workspace`

> 本文只写「做这个软件需要什么、现在做到哪」。历史过程与逐条缺陷记录不在本文范围。
> 每条结论都标了依据和适用边界；推测单独成章，冲突单独列出。

---

## 一、现在要做什么 / 最后交什么

### 1.1 这个软件是什么

一个 Android 应用（应用名 **StaticLog**，包名 `com.demo.zt`），把一套**已存在的设备端提权流程**包装成手机上可点按操作的界面：

- 界面来自两个开源项目（GhostLock-App 的版式 + 月虹提权助手的配对/终端页）
- 设备通信走应用内自实现的 **ADB over TCP** 协议栈（不需要电脑）
- 4 套设备端资源包内置在 `assets/variants/`，运行前推送到设备并执行

### 1.2 当前目标（唯一目标）

**在目标真机（iQOO 13 / PD2408）上，从 App 内部完成「配对 → 连接 → 执行」全链路，并拿到可复核的成功结果。**

### 1.3 工作范围

| 做 | 不做 |
|---|---|
| UI 移植与整合（GhostLock 主界面 + 月虹 ADB 页） | 不改写 `assets/variants/` 里的二进制（外部提供，禁止改字节） |
| 内核适配判定、开机自启、次数上限 | 不做 OTA 解析 / 配置生成 |
| 无线调试配对、自动重连 | 不做自定义弹窗缩放动画 |
| 4 套资源包内置与执行链 | 不做多设备并发 |

### 1.4 最终交付物（当前状态）

| 产物 | 路径 | 状态 |
|---|---|---|
| APK（release，已签名） | `APK-发给你/StaticLog-V46-release.apk` 45.71 MB<br>sha256 `CF1C01A1DAC5F2F66C9756B7D5DA8EB6A6AB04371FE4A80EA4B72E031BF9F364` | 已构建 |
| APK（debug） | `APK-发给你/StaticLog-V46-debug.apk` 53.03 MB<br>sha256 `74CA04C0525DEFBCC5BBD2602F37071E2D99C36C640C9EFAC44D1E5D6FE06D05` | 已构建 |
| 源码 | `ztui/app/src/main/java/com/demo/zt/**`（48 个 .kt） | |
| 自动化测试 | `ztui/app/src/androidTest/**`（`src/test` 274 条 JVM 单测全绿；设备测试 31 条上轮通过，本轮模拟器全掉线未重跑） | |
| 原生库 | `ztui/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libadb.so` | 已内置 |
| 资源包 | `ztui/app/src/main/assets/variants/{6-12g,6-16g,7-12g,7-16g}`（共 20.6 MB） | 已内置 |

### 1.5 验收范围

| 层级 | 状态 |
|---|---|
| 构建成功（debug / release） | ✅ 已验 |
| 安装 + 启动（模拟器） | ✅ 已验 |
| 界面渲染与导航（模拟器） | ✅ 已验 |
| ADB 传输（连接 / shell / 文件推送 / 远端执行） | ✅ 已验（模拟器真实 adbd） |
| 配对加密链路（SPAKE2 上下文） | ✅ 已验（仅上下文创建） |
| 资源包推送执行链（内核门禁 / 推送 / 权限 / 执行 / 标志解析） | ⚠️ 部分已验（模拟器上门禁正确拒绝） |
| 自动选择逻辑（代数判定 / 内存分档 / 失败规避 / 版本号判定） | ✅ 已验（45 条 JVM 单测，不依赖设备） |
| 真机上是否读得到软件版本号属性 | ❌ 未验（需真机；App 内「版本诊断」可自查） |
| 配对重试策略（重发现端口 / IPv6 回环 / 手填端口） | ⚠️ 代码完成，真机未验 |
| 「本地 ADB」身份自检（uid 2000） | ✅ 已验（模拟器实际弹出结果） |
| 启动权限 4 项（含电池优化/自启动跳转） | ✅ 已验（模拟器实际拉起通知授权弹窗） |
| 重启后自动开无线调试（免 root） | ✅ 已验双向（开写 1、关写 0，均回读确认） |
| 开机自动开启无线调试脚本（root 路径） | ⚠️ 脚本内容有单测；**写入需真机 root，未验** |
| 应用图标与名称 | ✅ 已验（模拟器桌面实际显示） |
| 界面「版本诊断」弹窗 | ✅ 已验（模拟器实际渲染并列出属性原值） |
| 版本号 → 标签联动 | ✅ 已验（模拟器注入 `ro.vivo.os.build.display.id=PD2408_A_16.9.1`，界面显示「自动（橘子6 · 12G）」+「第6代·版本号」） |
| **目标真机全链路** | ❌ **完全未验** |

---

## 二、最新要求 / 已作废的要求

### 2.1 当前有效要求

| 编号 | 要求 |
|---|---|
| R1 | 应用名 **StaticLog**，主界面标题**左对齐**，右上角齿轮 |
| R2 | 齿轮点开是**小选项面板**，不是整页设置 |
| R3 | 设置里「开机自动提权」开关 |
| R4 | 开机自启必须**内核完全适配**（版本 6.6.127 + 尾号 7741）才允许开启 |
| R5 | 每跑一次计数 +1，超过 **30 次自动关闭** |
| R6 | 开启自启时弹警告：「可能你的设备会一直重启，不过我们设置了 30 次上限」 |
| R7 | 关于页保留，写明所用开源库 |
| R8 | UI 用 **GhostLock 原版组件**（Miuix），不是仿写 |
| R9 | 配色跟随 GhostLock（深色 / 浅色双模式） |
| R10 | 资源包选择：**自动（括号显示当前用的包）**，下面逐个列出 |
| R11 | 配对只需一次，之后自动连（不需要重输配对码） |
| R12 | 月虹的权限引导页也要移植，配色改成 GhostLock |
| R13 | 界面里不能出现「不是原版」的自制组件（顶栏、设置面板、关于页、页面跳转都要用原版那套） |

### 2.2 已作废的要求

| 编号 | 原要求 | 作废原因 | 现行替代 |
|---|---|---|---|
| X1 | 两个独立按钮（A=Shizuku Shell、B=一键提 UI0） | 改为单按钮串两阶段 | 一个「执行」按钮 |
| X2 | 白底 + 蓝按钮的浅色 UI | 与原版不符 | R9 跟随系统深浅色 |
| X3 | 右上角齿轮 → 整页设置 | 原版是小选项面板 | R2 |
| X4 | 资源包叫 v6 / v7 | 改名 | R10 叫「橘子6 / 橘子7」 |
| X5 | 应用名「一键提权」 | 改名 | R1 StaticLog |
| X6 | 用 USB ADB 建立 uid 2000 通道 | 单机场景应用看不到自己的 USB 接口 | R11 无线调试 TCP |
| X7 | 自己仿写 GhostLock 风格 | 要求用原版源码 | R8 / R13 |
| X8 | 只做只读诊断 | 要求真实执行链 | 现行完整执行链 |

---

## 三、已确认的事和依据

> 口径：只写现有证据能直接支持的具体结论。每条给出「证据 → 观察到什么 → 适用条件 → 不能证明什么」。

### 3.1 构建环境（实测）

```
JDK        21.0.9            C:\Program Files\Java\jdk-21
Gradle     9.3.1             C:\gradle\gradle-9.3.1\bin\gradle.bat
Android SDK C:\AndroidSdk    platforms android-34 / android-37.0
                             build-tools 34.0.0 / 36.0.0 / 37.0.0
NDK        r27c              iqoo13_fw\android-ndk-r27c
```
**依据**：`gradle --version`、`sdkmanager --list` 输出。
**不能证明**：换机器后的路径与版本一致。

### 3.2 版本组合是硬约束（实测）

```
AGP 9.1.1 + Gradle 9.3.1 + compileSdk 37   → BUILD SUCCESSFUL
AGP 8.7.3                                → 报 "Minimum supported Gradle version is 8.9"
jvmTarget 17                             → 报 "Cannot inline bytecode built with JVM target 21 into ... 17"
jvmTarget 21                             → BUILD SUCCESSFUL
```
**依据**：上述报错原文与切换后的成功日志。
**不能证明**：其它 AGP/Gradle 组合是否可行（未穷举）。

**为什么必须 jvmTarget 21**：`miuix-nav 0.9.4` 是用 JVM 21 编译的，页面跳转要用它的 `NavDisplay`。

### 3.3 工程配置（实测，取自 `ztui/app/build.gradle.kts`）

```
applicationId  com.demo.zt
compileSdk     37          targetSdk 36          minSdk 26
versionCode 1              versionName "1.0"
release 签名   ztui/.android/sl-release.jks（alias staticlog）
证书           DN=CN=StaticLog, OU=Local, O=StaticLog, L=NA, ST=NA, C=CN
               SHA-256 d4d2421cae5cde7ee549cc4db0b511ef93127de68fe8fe8c3ef17f5874103f44
```
**依据**：`apksigner verify --print-certs` 输出 V2 Signer 段。
**不能证明**：该自签证书有任何第三方可信度，仅适用于本地侧载。

### 3.4 内置资源包（实测，目录清单）

| 目录 | 文件 | 流程 |
|---|---|---|
| `6-16g` / `6-12g` | `zt_run`, `zt_su`, `zt_fanout.zte`, `zt_su.zte` | 第 6 代：写命令文件 + `zt_su --daemon` 守护 |
| `7-16g` / `7-12g` | `zt_run`, `zt_fanout.zte`, `zt_prep.zte` | 第 7 代：直接 insmod 内核模块 |

推送后权限：`zt_run`/`zt_su` → 755，`*.zte` → 644。
**依据**：`assets/variants/` 实际文件；执行命令取自资源包自带的官方一键脚本原文。
**不能证明**：这些包在目标真机上一定成功（见第四章 P1）。

### 3.5 原生库 `libadb.so`（实测）

- APK 内含 4 个 ABI：`lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libadb.so`
- 来源：`Stellar/manager/src/main/jni/adb_pairing.cpp`（SPAKE2 + AES-128-GCM 的 JNI 桥），
  用 NDK r27c + boringssl prefab（`io.github.vvb2060.ndk:boringssl:20250114`）编译
- 导出符号实测存在：
  `Java_com_demo_zt_adb_PairingContext_nativeConstructor / nativeMsg / nativeInitCipher / nativeEncrypt / nativeDecrypt / nativeDestroy`
- 运行时日志：`AdbPairClient: libadb.so 已加载（SPAKE2 / ed25519 / AES-GCM 导出就绪）`

**依据**：`llvm-nm -D` 符号列表；应用 logcat 原文。
**不能证明**：与真实设备的完整配对握手可用（只验到上下文可创建）。

### 3.6 ADB 传输层（实测通过，模拟器真实 adbd）

| 用例 | 断言 | 结果 |
|---|---|---|
| `shellWorks` | 连接 + `id -u` 返回数字 | ✅ |
| `syncPushLargeFileIntegrity` | **6 MB** 数据 push，远端大小与 sha256 与本地一致 | ✅ |
| `syncPushExecutableAndRun` | push + chmod 755 + 远端执行，回读到预期输出 | ✅ |

**依据**：`ztui/app/build/outputs/androidTest-results/connected/debug/*.xml`（`tests=22 failures=0`）。
**不能证明**：真机无线调试的 TLS + 签名分支（模拟器 adbd 未强制鉴权）。

### 3.7 设备信息与自动选择（实测）

- 内存读取：`ActivityManager.MemoryInfo.totalMem` 优先，`/proc/meminfo` 兜底
  实测日志：`内存读取成功：source=ActivityManager totalMem=9396781056 B (8.75 GiB)`
- 16G/12G 阈值 **13 GiB**：厂商标 16G 的机型 `totalMem` 实报 15.0–15.5e9 B（13.97–14.41 GiB）；
  12G 机型实报约 10.8 GiB
- 界面「载荷版本」下方显示判定摘要，形如 `第7代·历史记录 · 8.75GiB→12G`

**依据**：落盘日志 + 模拟器截图。
**不能证明**：目标真机的实际内存落档（未测）。

失败记忆（2026-11-03 追加）：`PayloadRunner` 在四种失败出口都会写 `lastFailureFamily`
（执行异常 / `IQBIND_ABORT` / 无成功标志 / root 复核未过），
下次「自动」据此改试另一代；一旦某代成功则清掉失败记录。
**不会**在本次执行里自动换代码重跑——那会在设备上留下两次尝试的副作用。

### 3.8 「自动」选择的三层依据（设计事实）

| 优先级 | 依据 | 是否算「已确认」 |
|---|---|---|
| A | **历史成功记录**：上次某代真的执行成功过（成功复核到 uid=0 才写入；成功时清掉失败记录） | 是 |
| B | **软件版本号**：前两位 **16 → 第6代**，**17 → 第7代**（设备方给出的确定对应） | 是 |
| C | **已验证映射规则**：完整 `Build.FINGERPRINT` 精确匹配（**当前为空表**） | 是 |
| D | **失败规避**：上一次跑失败的那一代，本轮改试另一代 | 否（换的另一代未证实） |
| E | **兜底**：取较新代数，界面标「未确认」 | 否 |

软件版本号的读取顺序（第一条能解析出代数的就采用）：
`ro.vivo.os.build.display.id` → `ro.vivo.os.version` → `ro.vivo.product.version`
→ `ro.rom.version` → `ro.originos.version` → `ro.vivo.os.name`
→ `ro.system.build.version.incremental` → `Build.DISPLAY` → `Build.VERSION.INCREMENTAL`
→ `Build.FINGERPRINT`。

解析器按「三段式 → 字母前缀三段式 → 两段式 → 字母前缀两段式 → 纯数字」依次匹配，
并且要求数字左边不紧贴字母/数字：
`PD2408_A_16.9.1` → 16 ｜ `V16.9.1` → 16 ｜ `16.9.1.W10.V000L1` → 16
`PD2408` → 不认识 ｜ `SP1A.210812.016` → 不认识 ｜ `RRE1.211202.001` → 不认识

**界面可诊断**：设置面板 →「版本诊断」，列出每个候选属性的**原始值**与解析结果，
不需要 logcat。这条是给真机排查用的：若显示「未读到版本号」或「读到 x（不认识）」，
把屏幕内容反馈即可定位。

**曾出过的坑（已修，勿重犯）**：`Variant.resolve()` 一度自己拼了一套 probe
（版本号传默认 `null`、规则传空表），与 `PayloadResolver.decide()` 不是同一条链，
导致「版本诊断能识别 16，但自动标签仍显示橘子7」。
现在 `resolve()` 直接委托 `PayloadResolver.decide()`，标签也从同一个
[PayloadDecision] 取变体；`AutoLabelConsistencyTest`（5 条）专门守这条不变量。

关键前提：**两代资源包面向同一内核**（文件名均为 `66127-7741-*`），
所以「该用第几代」无法从内核 / SDK / 机型推导。

### 3.9.0 内核门禁判据已放宽（2026-11-03 真机反馈）

真机反馈：**明明是 6.6.127，却被判不支持、挡在门外。**

根因：老判据要求「`abogki` 后面的数字末四位 = 7741」，
但内核串格式并不统一；没有 `abogki` 段时，老实现会退化成
「取整串最后一个数字段」（常常是 `-4k` 里的 `4`），于是必然不匹配。

现在的判据：
1. 版本串含 `6.6.127`
2. **版本串里含 `7741` 即算尾号匹配**（不再依赖 `abogki` 的位置）

`Verdict` 增加 `tailSource` 记录判据来源：
`ABOGKI`（最可靠）/ `CONTAINS` / `GUESSED` / `NONE`。

同时把失败原因显示到状态卡上（原来只有一句「未找到匹配的内核配置」）：
```
版本不匹配（期望 6.6.127；实际读取：5.4.32-...）
未在版本串里找到尾号 7741（实际读取：6.6.127-android16-8-gabc-4k）
```
内核原文进文案，用户能直接看出是版本不对还是尾号没认出来。

`KernelGateTest`（12 条）覆盖：带/不带 `abogki`、`abogki77410`、
版本错、尾号缺、空串、null、失败文案必须含内核原文。

### 3.9 内核门禁（实测，模拟器）

- 门禁判据：内核 release 含 `6.6.127`，且 `abogki` 后数字末四位为 `7741`
- 模拟器内核 `5.4.32-perf-...` → 界面显示红色「不支持」✅
- 门禁拒绝时**不写入任何文件**（测试断言 `/data/local/tmp` 无残留）✅

**依据**：`PayloadGateTest` 通过；模拟器截图。
**不能证明**：目标真机会显示绿色「支持」（真机内核串未在 App 内实测）。

### 3.9.1 配对失败的真正根因：平台 exporter 被删除（2026-11-03 真机实证）

真机通知原文：
```
NoSuchMethodException: com.android.org.conscrypt.Conscrypt.e…
```

**根因**：ADB 无线配对必须用 TLS 1.3 的 keying material exporter
（label `adb-label`）派生会话密钥。平台把这个方法放在隐藏类
`com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` 里，
**Android 16 已移除该方法**，反射调用直接抛 `NoSuchMethodException`，
配对在握手后第一步就失败（与配对码、端口无关）。

**修复**：自带一份 Conscrypt（`org.conscrypt:conscrypt-android:2.5.2`），
用它的**公开** `exportKeyingMaterial` API，并用它的 Provider 建 TLS 上下文
（平台 Provider 造的 socket 导不出 keying material）。

- 新增 `adb/TlsExporter.kt`：三段式回退
  （自带 Conscrypt → 平台隐藏类 → 反射扫描同名静态方法）
- `AdbKey` 暴露 `keyManagerForTls` / `trustManagerForTls` 供其初始化
- 附带代价：APK 增大 ~8 MB（4 个 ABI 的 `libconscrypt_jni.so`）

**实测（模拟器 Android 15）**：`TlsExporterTest` 4 条断言全过——
自带 Conscrypt 初始化成功、上下文协议 `TLSv1.3`、诊断可读。

### 3.10 配对前台服务与通知（实测）

- `AdbPairingService` 已在 `AndroidManifest.xml` 注册，`foregroundServiceType="specialUse"`
  并声明 `FOREGROUND_SERVICE_SPECIAL_USE`
- 实测：`startForegroundCount=1`、`isForeground=true`，
  通知栏可见「正在搜索配对服务 / 停止搜索 / 搜索不到配对」

**依据**：`dumpsys activity services`、`dumpsys notification`、通知栏截图。
**不能证明**：输入配对码后的成功路径（见第四章 P2）。

**通知不刷新的修复（2026-11-03 真机反馈）**：只调 `startForeground` 更新通知时，
部分 ROM 的通知栏不重新渲染，表现为「要手动点一下才刷新」。
新增 `pushNotification()`：`startForeground` 之后再显式
`NotificationManager.notify()` 一次，强制刷新；失败自动降级不抛异常。
该函数替换了全部 5 处更新点。

### 3.11 界面已改用原版组件（实测渲染）

| 位置 | 组件 |
|---|---|
| 主界面顶栏 | Miuix `SmallTopAppBar` + `IconButton` + `MiuixIcons.Settings`（标题放左侧槽实现左对齐） |
| 主界面列表 | `LazyColumn` + `scrollEndHaptic` + `overScrollVertical` + `nestedScroll` |
| 设置面板 | `OverlayDialog` + `Card` + `SwitchPreference` + `ArrowPreference` |
| 关于页 | `SmallTopAppBar` + hero 区 + `SmallTitle` 分组 + `Card`/`ArrowPreference` 条目 |
| 页面跳转 | `NavDisplay` + `NavBackStack` + `NavSwipeDirection`（含滑动返回、圆角转场） |
| 窗口底色 | `values` / `values-night` 双份 `window_background`，跟随系统深浅色 |

**依据**：模拟器截图与滑动返回实测。
**不能证明**：与原版逐像素一致（只做到组件与版式一致）。

### 3.11.1 应用图标与名称（2026-11-03 补齐，实测）

之前 `AndroidManifest.xml` **没有** `android:icon`，装出来是系统默认机器人图标；
`android:label` 也还是脚手架名 `ZT Compose Demo`。现均已修正：

| 项 | 值 |
|---|---|
| `android:icon` | `@mipmap/ic_launcher` |
| `android:roundIcon` | `@mipmap/ic_launcher_round` |
| `android:label` | `StaticLog` |
| 背景色 | `#F2F2F2`（取源图边缘近白） |

图标资源（源图 `assets-src/icon-source.webp`，1252×1252）：
- 传统：`mipmap-{mdpi..xxxhdpi}/ic_launcher.png`（48/72/96/144/192）与圆形版
- 自适应：`mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_foreground.png`
  （108dp 画布，实测标志占 **61.1%**，符合 66dp 安全区）
- 单色（API 33 主题图标）：`mipmap-anydpi-v33/ic_launcher.xml` + `ic_launcher_monochrome.png`
  （纯黑剪影 + 透明底）

**实测**：`aapt2 dump badging` → `application: label='StaticLog' icon='res/…/ic_launcher.xml'`；
桌面图标显示正常（幽灵+毛线帽+挂锁）。

### 3.11.2 「本地 ADB」改为身份自检（2026-11-03 按需求改）

原行为：点「本地 ADB」跳转月虹的终端页（`ui/LocalAdbScreen.kt`）。
现行为：**该页面已删除**，入口改为一次自检 —— 连上之后执行 `id`，
判断当前身份是不是 **uid 2000（shell）**，结果用 `OverlayDialog` 展示
（结论 + 期望值 + `id` 原文）。

| 项 | 说明 |
|---|---|
| 判定逻辑 | `AdbSelfCheck.parseUid()` / `evaluate()`，纯函数可单测 |
| 通过条件 | uid == 2000；uid == 0 也提示「可用」；其它 uid 标为异常 |
| 未连接时 | 自动跳到配对页（Starter） |
| 入口文案 | 「本地 ADB / 自检当前身份是否为 uid 2000」 |

**两个踩过的坑（勿重犯）**：
1. Compose 状态写在**后台线程**不触发重组 → 自检跑完界面不动。
   必须 `Handler(mainLooper).post { }` 回主线程写。
2. Miuix `OverlayDialog` 默认 `renderInRootScaffold = true`，
   放在 `Scaffold` **外面**不会渲染。弹窗必须放进 Scaffold 内容区。

**实测（模拟器 Android 15）**：点入口后弹出「自检通过：当前身份为 uid 2000（shell），
ADB 通道可用」，并列出 `id` 完整输出。

### 3.11.2b 开机自启为什么不触发（2026-11-03 真机定位）

真机反馈：**无线调试确实开机自启了，但提权没跑。**

两个原因叠加：

1. **`startActivity` 在开机广播里被系统拒绝**
   Android 10+ 限制后台启动 Activity，`BootReceiver` 里调用
   `context.startActivity()` 会被**静默拒绝**，没有任何报错。
2. **一次「不支持」就把自启永久关掉**
   `MainActivity` 里原本有：
   ```kotlin
   if (!supported && autoRunOnStart) LaunchedEffect { AutoBoot.disable(context) }
   ```
   叠加当时的内核判据 bug（6.6.127 被误判），自启开关被自动关掉，
   用户看到开关开着、其实早被关了。

**修复**
- 新增 `AutoBootService`（`specialUse` 前台服务）：开机广播直接启动它，
  服务内等待 ADB 就绪（最长 90s，每 6s 重试）→ 跑载荷 → 发通知 → 自停。
  **全程不需要界面**，绕开后台上限。
- `BootReceiver` 改为只做两件事：恢复无线调试开关 + 启动该服务；不再 `startActivity`。
- 开机广播补 `QUICKBOOT_POWERON` / `LOCKED_BOOT_COMPLETED`（部分厂商用前者）。
- 删掉「内核不支持就 disable 自启」的逻辑，改为只记一条日志。

新增 `BootAutostartPathTest`（6 条）在源码层面钉住：
开机广播代码里不得出现 `startActivity`（注释不算）、必须启动 `AutoBootService`、
Manifest 必须声明 `specialUse` 前台服务、必须监听 QUICKBOOT、
不得再出现 `AutoBoot.disable` 的自动关闭分支、服务必须等 ADB 且有超时上限。

**未验证**：真机重启后的服务实际拉起与执行（模拟器全掉线，本轮无法跑设备测试）。

### 3.11.3 启动权限与「开机自动开启无线调试」（2026-11-03）

**启动权限**：引导页从 2 项扩到 4 项——

| 项 | 判定 | 动作 |
|---|---|---|
| 读取应用列表 | `getInstalledApplications().size > 1` | 无开关，如实提示可跳过 |
| 通知权限 | `POST_NOTIFICATIONS` + `areNotificationsEnabled()` | 拉起系统弹窗 |
| **忽略电池优化** | `PowerManager.isIgnoringBatteryOptimizations` | 跳 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| **允许开机自启动** | **无法可靠查询**（无公开 API），返回 null=未知 | 跳厂商页面（vivo/iQOO 优先） |

新增 `ui/AutostartHelper.kt`：按厂商逐个尝试自启动页（`com.vivo.permissionmanager` /
`com.iqoo.secure` / miui / huawei / oppo / samsung），全失败退应用详情页再退设置首页。
**不假装能申请**——只能把用户带到正确位置。

**重启后自动开无线调试（无需 root）**（`WirelessAdbAutoEnabler.kt`，2026-11-03 实测通过）：

Android 11+ 的无线调试开关就是全局设置项 `adb_wifi_enabled`，系统 `AdbService` 监听它。
应用拿到 `WRITE_SECURE_SETTINGS` 即可直接写——**不需要 root，也不需要 Tasker**：

```
# 一次性授权（只需一次，卸载重装后要重做）
adb shell pm grant com.demo.zt android.permission.WRITE_SECURE_SETTINGS
```

之后 `BootReceiver` 在开机广播里写 `adb_wifi_enabled=1`，开关自动恢复。

**实测（模拟器 Android 15）**
```
写入前  settings get global adb_wifi_enabled → 0
应用拨开关 → 系统弹出真实网络授权框「要允许通过此网络进行无线调试吗？-- Linksys_…」
勾选「始终允许」并确认后：
  adb_wifi_enabled → 1
  service.adb.tls.port → 34075   ← adbd TLS 端口已起
  persist.adb.tls_server.enable → 1
```

**注意一个容易误判的中间态**：刚写入时 `settings get` 可能仍返回 `0`，
因为系统在等用户确认「是否允许此网络调试」。确认后才变 `1`。
所以「写完立刻读回」不能当作失败判据，代码里已按「写入成功但系统未生效」单独提示。

**开机自动开启无线调试（需 root）**（`BootAdbEnabler.kt`，root 路径，作为无网络授权时的备选）：
- 现实约束：Android **重启后必定关闭无线调试**，应用层既不能申请也不能保持。
- 唯一可落地路径：**拿到 root 后**写开机脚本
  `/data/adb/service.d/staticlog_adb.sh`：
  ```
  setprop service.adb.tcp.port 5555
  stop adbd
  start adbd
  ```
  之后每次开机 adbd 监听 5555，`AdbAutoConnector` 的固定端口回退正好是 5555，
  不用配对、不用再点开关。
- 边界：依赖 Magisk / KernelSU 的 `service.d`；5555 是**明文 tcp adb**，仅限可信网络。
- 界面：设置 →「开机自动开启无线调试」，写入结果用 `OverlayDialog` 展示并附限制说明。

### 3.11.4 三个开关的语义（易混，务必按此理解）

| 设置项 | 作用 | 前提 | 代价 |
|---|---|---|---|
| **开机自动提权** | 开机后自动跑一次提权流程，计数 +1，满 30 次自动关闭 | 内核完全适配（6.6.127 + 7741） | 可能反复重启 |
| **重启后自动开无线调试（免 root）** | 开机时把系统「无线调试」开关拨上去（写 `adb_wifi_enabled=1`） | 一次性 `pm grant WRITE_SECURE_SETTINGS` | 会弹一次网络授权框；厂商可能拦截 |
| **开机直接开网络 ADB 端口（需 root）** | 用 root 写 `/data/adb/service.d` 脚本，让 adbd 固定监听 5555 | 需要 root | 明文 tcp，不做加密配对 |

**「关不掉」的修复（2026-11-03）**：根因是 `SharedPreferences.apply()` 异步写盘——
用户关掉开关后应用被杀，这次写入丢失，重启后照跑，现象就是关不掉。
已全部改为 `commit()` 同步写；开关切换后**从存储回读**一次，避免显示与实际脱节；
并加 `BootFlagPersistenceTest`（4 条）在源码层面守住这条规则。

### 3.11.5 无线调试开关改为双向生效（2026-11-03 实测）

原实现只在「开」时写 `adb_wifi_enabled=1`，关的时候只清自己的偏好、**没动系统开关**。
现在 `applyNow(context, enable)` 双向都写：

```
点开：写入 adb_wifi_enabled=1，回读=true   → 系统值 1
点关：写入 adb_wifi_enabled=0，回读=false  → 系统值 0
```
（模拟器实测，logcat 原文 `WirelessAdbAuto: 写入 adb_wifi_enabled=0，回读=false`）

实现上把「写设置」与「结果判定」拆成 `applySetting(enable, writeSetting, readSetting)`，
纯逻辑可单测；关闭方向只要写成功即算成功（系统本来没开也正确）。

### 3.11.5.1 root 路径的循环依赖（重要，别再踩）

`service.d` 只有 **Magisk / KernelSU 开机时才会扫**。
如果 root 是靠应用内置载荷在运行时临时打上的（重启即失效），
那么「写 service.d 脚本让开机恢复 ADB」就是**循环依赖**：
为了恢复 ADB 才需要它，但它本身需要持久 root 才能执行。

已按此加了前置判定 `BootAdbEnabler.hasPersistentRootServiceDir()`：
- 执行 `id -u` 确认真的是 root（避免权限不足被误判成「没有」）
- 判据：`/data/adb/magisk` 或 `/data/adb/ksu` 存在；或 `/data/adb/service.d` **非空**
  （空目录不算——那可能就是我们自己 mkdir 出来的）
- 只接受完整结果行，命令回显/报错文本里碰巧含标记不会误判
- 加 `timeout 5` 限制设备端执行时间
- `false` → **直接返回失败、不写文件**，文案引导改用免 root 方案
- `null`（探测失败）→ 照常尝试，但成功文案里提示「重启后可能不生效」

**结论**：靠载荷临时 root 的设备，这条路走不通，应该用免 root 的
「重启后自动开无线调试」（写 `adb_wifi_enabled`，只要有 `WRITE_SECURE_SETTINGS` 就能在开机广播里执行）。

### 3.11.6 ~~「拿到 ADB 后自动执行」~~（**已彻底移除**，2026-11-03）

曾加过「配对页返回且已连接 ADB 时自动执行一次提权」的钩子（`AutoRunAfterAdb`），
默认关闭。但实测反馈：**一配对就自动开始提权**，体验错误。

已整个删除：
- `MainActivity.popBack()` 恢复为纯弹栈，不再有任何自动执行分支
- 设置页该开关删除
- `AutoRunAfterAdb.kt` 与对应单测删除

**现在的语义**：配对只负责「连上」，是否跑载荷只由「执行」按钮或开机自启触发。
不要在 `popBack` 之类的通用返回路径上挂副作用。

### 3.11.6c **真正导致「一配对就提权」的地方（2026-11-03 定位并修复）**

上一轮删掉 `popBack` 的自动执行后仍会重现，继续查发现真正的触发器在配对页里：

```kotlin
// Starter.kt（修复前）
const val userCommand = "/data/local/tmp/zt_run --run"   // 载荷启动器
const val wirelessDebuggingCommand = userCommand        // ← 配对连接后执行它
```

`StarterScreen.startAdbConnection()` → `AdbWirelessHelper.startStellarViaAdb()`
→ `client.shellCommand(Starter.wirelessDebuggingCommand)`，
于是在**配对/连接成功的那一刻就直接跑了载荷**。
这是月虹原版「连上后拉起 Stellar 服务」的设计，被移植成配对页后语义就错了。

**修复**
- `wirelessDebuggingCommand` 改为 `"id"`（只读身份检查），并在注释里写明为什么不能是载荷
- `StarterScreen` 的步骤 UI 增加 `uid=` 分支：识别 `id` 输出，正常标记
  「连接成功 / 验证通过」，否则五个步骤会卡住、自动关闭也不触发
- 新增 `PairingMustNotRunPayloadTest`（3 条）在源码层面钉死：
  配对命令不得含 `zt_run`、不得别名到 `userCommand`、`popBack` 内不得出现 `runPayload`

**教训**：移植别人页面时，**它「连上之后顺手做的事」必须逐条审**，
不能只改外观。这里顺手做的事就是执行载荷。

### 3.11.6b 历史记录（原始实现，仅供追溯）

`AutoRunAfterAdb.kt`：配对页返回且 `AdbHolder.client != null` 时，若该开关为 true
则执行一次提权流程。**默认 false**，避免未预期的自动执行。
用 `commit()` 同步写盘（与本项目其它开关一致）。

> 注：这一项是额外加的，需求方表示「不用管」，默认关闭不影响原有流程。

### 3.11.7 目标设备无持久 root 后的调整（2026-11-03 确认）

需求方确认：**目标设备没有持久 root**（无 Magisk/KernelSU，root 由载荷临时打上）。
因此 root 的 `service.d` 方案在该设备上永远无效，做了两处调整：

1. **设置页按探测结果隐藏该行**：打开设置时后台探测
   `hasPersistentRootServiceDir()`；`false` → 整行不显示；`true` → 显示；
   `null`（无连接/探测失败）→ 显示并标注「无法确认本机是否有持久 root」。
   实测（模拟器无 ADB 连接）：显示「无法确认本机是否有持久 root」，符合预期。
2. **应用启动自愈补写**：厂商 ROM 可能拦截 BOOT_COMPLETED 或不给自启动权限，
   所以每次打开应用时也补一次——若「开关已开 + 有权限 + 当前不是开启状态」，
   就写 `adb_wifi_enabled=1`。
   决策抽成纯函数 `WirelessAdbAutoEnabler.shouldApplyOnLaunch(enabled, hasPermission, current)`。
   实测：手工把系统值置 0 → 重启应用 → 系统值被补回 1，
   logcat：`WirelessAdbAuto: 写入 adb_wifi_enabled=1，回读=true`。

**结论（给接手者）**：这台设备只能走免 root 路径。
root 路径保留代码但在无持久 root 的机器上不展示。

### 3.12 权限引导页（实测）

- `QUERY_ALL_PACKAGES` 为 `prot=normal` 权限，**安装即授予，系统设置里没有开关**
- 已显式声明该权限；判定只按「appops 未显式拒绝 + 可枚举应用数 > 1」
- 「重新检查」不再跳应用详情页；另有「跳过，直接继续」入口

**依据**：`dumpsys package permissions` 显示 `prot=normal`；强制 appops 拒绝后的界面实测。
**不能证明**：特定厂商 ROM 的包可见性策略（未测）。

---

## 四、还没验证的推测

置信度仅表示现有支持强度，不改变「未验证」状态。

| 编号 | 推测 | 依据 | 置信度 | 反证条件 |
|---|---|---|---|---|
| P1 | 目标真机上资源包推送并执行能成功 | 推送链路（含 6MB）已在模拟器真 adbd 验通；执行编排照抄官方脚本 | 中 | 真机执行后 `/data/local/tmp` 无文件，或日志无成功标志 |
| P2 | 真机上 SPAKE2 配对握手能完成 | 原生库已内置、上下文可创建、密钥已统一 | 中 | 配对码提交后通知仍显示失败，原因含 TLS/SPAKE2 错误 |
| P3 | 配对一次后能自动重连 | ADB 协议机制：配对只交换密钥；传输层已验通 | 中 | 重启后自动连接返回 false |
| P4 | mDNS 能发现配对/连接端口 | 现有实现依赖系统 NSD | 中 | 日志出现「未发现服务」 |
| P5 | 开机自启能跑通 | `BootReceiver` + `AutoBoot` 逻辑完整 | 低（未测） | 重启后无自动执行 |
| P6 | 真机上内核判定显示绿色「支持」 | 目标内核串已知含 `6.6.127` 与 `7741` | 高 | 显示红色「不支持」 |
| P7 | arm64 真机上 Compose 界面正常渲染 | 模拟器 x86_64 已渲染成功 | 高 | 真机崩溃或错位 |
| P8 | 横屏下「本地 ADB」页布局正确 | 竖屏正常 | 低 | 横屏下终端输出区被挤成 0 高 |

---

## 五、试过不行的路和原因

| 编号 | 尝试 | 失败表现 | 结论 |
|---|---|---|---|
| F1 | USB ADB 建立 uid 2000 通道 | 应用枚举不到自己的 ADB USB 接口 | **方案不可行**，改无线调试 TCP |
| F2 | `dev.mobile.dadb` 库 | 中央仓库与镜像均 404 | 库不存在 |
| F3 | 直接构建 GhostLock-App 原工程 | 需 Gradle 9.7.1 + AGP 9.1.0 + Kotlin 2.4.20，公共仓库拉不到 | 只能移植组件 |
| F4 | `AGP 8.7.3 + Gradle 8.7` | `Minimum supported Gradle version is 8.9` | 版本不匹配 |
| F5 | `sdkmanager "platforms;android-37"` | `Failed to find package` | 正确名是 `platforms;android-37.0` |
| F6 | 用 base64 经 shell 推资源包 | 6.2 MB 文件 → 8.3 MB 单条命令，超 shell 上限 | 改 **sync 协议流式推送** |
| F7 | `RegisterNatives` 注册 JNI 方法 | `Failed to register non-native method ...$Companion.nativeConstructor`（`@JvmStatic` 使其编到外层类） | 改**标准 JNI 导出名** |
| F8 | sync `SEND` 用 `路径\0,mode` | `FAIL: missing , in ID_SEND_V1`（adbd 先按 NUL 截断） | 必须 `路径,mode\0` |
| F9 | CNXN 取 `arg0` 当单包上限 | `arg0` 是版本号（16777217），发出超限包 → `FAIL` | 正确字段是 `arg1` |
| F10 | 每条 shell 命令固定 `localId=1` | 上一条流的迟到消息被下一条读走，输出串味 | 每流新 id + 按 `arg1` 过滤 + 串行化 |
| F11 | 配对与自动连接各自建密钥 | 两处用不同 SharedPreferences，等于两把不同 RSA 密钥；配对授权的是 A，连接用的是 B | 统一到 `AdbKeyProvider` 单例 |
| F12 | 内存阈值取 14 GiB | 16G 机型实报 15.0e9 B = 13.97 GiB，被判成 12G | 阈值改 **13 GiB** |
| F13 | `AdbPairingService` 不在 Manifest 声明 | `startForegroundService` 抛异常，服务从未启动，永远没有通知 | 补 `<service>` + `foregroundServiceType` |
| F14 | Manifest 用 `Theme.Material.Light.NoActionBar` | 深色模式下状态栏区域是浅色窗口底，右上角发白 | 改 `values`/`values-night` 双份底色 |
| F15 | 手写 `Row + Text("⚙")` 顶栏、Material3 设置面板 | 与原版不一致 | 全部换 Miuix 原版组件 |
| F16 | `showAdb` 状态从未置 true | 「本地 ADB」入口实际跳配对页，终端页不可达 | 改为按连接状态分流 |
| F17 | 从设备属性推导「该用第几代」 | 两代包面向同一内核，无可用判据 | 改三层依据（历史 / 规则 / 兜底） |
| F18 | 硬连 `127.0.0.1` 做配对 | `ConnectException: failed to connect to /127.0.0.1 (port …)` | 改按 mDNS 解析出的 host，失败再退 `127.0.0.1` |

---

## 六、下一步最小的一件事

### 6.1 单一动作

**在目标真机（iQOO 13 / PD2408）上安装 release APK，走完「配对 → 连接 → 执行一次」全链路。**

### 6.2 起点条件

- 真机通过 USB 连上，`adb devices` 能看到该设备序列号
- 使用 `StaticLog-v1.0-release.apk`（sha256 见 1.4）
- 若设备此前装过 debug 版，需先卸载（签名不同会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）

### 6.3 操作

```powershell
$adb = 'C:\Program Files\Rex\小天才安装工具（高级版）\adb.exe'
$d   = '<真机序列号>'
& $adb -s $d install -r "StaticLog-v1.0-release.apk"
& $adb -s $d shell "am start -n com.demo.zt/.MainActivity"
```

### 6.4 合格判据（逐条可观察，全中才算通过）

| # | 判据 | 观察位置 |
|---|---|---|
| 1 | 状态卡显示**绿色「支持」** | 主界面顶部卡片 |
| 2 | 「载荷版本」显示**自动（…·16G）**，下方摘要含 `→16G` | 主界面载荷版本行 |
| 3 | 开启无线调试 + 配对窗口后，**通知栏出现**「正在搜索配对服务」 | 系统通知栏 |
| 4 | 输入 6 位配对码后，通知变为**配对成功**类文案，不再显示失败原因 | 系统通知栏 |
| 5 | 「本地 ADB」页右上角状态显示**「已连接」** | 本地 ADB 页 |
| 6 | 点「执行」后日志出现 **`[完成] 提权成功`** | 主界面日志面板 |

若第 6 条出现失败，日志必须给出可定位原因（门禁拒绝 / 推送失败 / 无成功标志 / 复核未过）。

### 6.5 失败出口（按判据编号）

| 卡在 | 查什么 |
|---|---|
| 1 | `KernelGate` 的尾号提取；`adb shell uname -r` 实际串 |
| 2 | `DeviceInfo.memorySource` 日志；是否落到 `/proc/meminfo` 或 0 |
| 3 | `AdbPairingService` 是否启动（`dumpsys activity services com.demo.zt`） |
| 4 | 通知里的失败原因原文：密码错 / TLS / 端口不通 三者可区分；再看 `logcat -s AdbPairClient AdbClient` |
| 5 | 自动重连日志；`AdbKeyProvider` 是否只有一把密钥 |
| 6 | `logcat -s AdbClient PayloadRunner`；`/data/local/tmp` 是否有文件 |

---

## 七、冲突与未决项

| 编号 | 冲突 / 未决 | 说明 |
|---|---|---|
| C1 | ~~「自动识别橘子系统」无判据~~ **已解决** | 设备方给出确定判据：软件版本号前两位 16 → 第6代，17 → 第7代。已实现为判定链第 B 层，并有 15 条单测覆盖（含 `PD2408_A_16.9.1` 这类带型号前缀的串）。**待真机确认**该属性在目标机型上读得到 |
| C2 | 规则表仍为空 | `PayloadResolver.verifiedRules` 是备用层（C 层），有版本号判据后不再是主路径，保留作为特殊情况补丁位 |
| C3 | 真机不可用 | 交接时无目标真机接入，第六节全部判据均无法执行 |
| C4 | 配对成功路径未验 | 加密库可用、密钥已统一，但与真实设备的握手从未跑通过 |
| C5 | 横屏布局 | 「本地 ADB」页在 1600×900 横屏下终端输出区被挤成 0 高；竖屏正常。未修 |
| C6 | 三个疑似死代码文件 | `SettingsScreen.kt` / `AdbScreen.kt` / `AdbController.kt` 全项目无引用（全量 grep），未删以免扩大改动面 |
| C7 | APK 体积 | 40–49 MB：20.6 MB 资源包 + 3.5 MB 四架构 `libadb.so`。若要减：按机型只留一套资源包 + 只留 `arm64-v8a` |
| C8 | 权限引导页语义 | `QUERY_ALL_PACKAGES` 是 normal 权限，无开关；部分厂商 ROM 可能限制包可见性，届时该功能降级但不阻塞使用 |

---

### 3.13 版本号机制（2026-11-03 补齐）

**问题**：出了 16 个包，`versionCode` 一直是 1、`versionName` 一直是 `1.0`，
界面还写死 `v1.0`——用户完全分不清手上装的是哪一版。

**现在的规则**：编号 = **累计出包次数**，不是语义化版本。

| 项 | 说明 |
|---|---|
| 来源 | `ztui/version.properties`（`versionCode` / `versionName`） |
| 命名 | `V<次数>`，例如 `V17` = 第 17 个 release 包 |
| 界面 | 关于页与设置页读 `BuildConfig.VERSION_NAME`，不再写死 |
| APK 文件名 | `StaticLog-V<n>-release.apk` / `-debug.apk` |
| 出包纪律 | **每次出包必须 `versionCode` 与 `versionName` 同时 +1**，且 code 单调递增（否则无法覆盖安装） |

已交付记录（有哈希可查的 16 个）：
```
V1  A68D0C89   V2  2DFA3DD5   V3  0BDE693C   V4  2ECC7045
V5  346A18A0   V6  30915ADB   V7  820CA03A   V8  22AAFFFF
V9  60E3A319   V10 98F17C18   V11 BDEDC740   V12 905488F7
V13 F2F2B572   V14 479F6AFA   V15 62DC18DA   V16 1840D908
V17 203EF4D1\nV18 4378536F\nV19 93C56D9C\nV20 948B3273\nV21 A2FA7034\nV22 2D425F39\nV23 A4DC02B9\nV24 8EDF931C\nV25 FAAE5625\nV26 F49CA554\nV27 335AE3DB\nV28 20336F33\nV29 0B049C10\nV30 DE1C9477  ← 当前
```

`BuildVersionTest`（5 条）守住：`versionName` 必须是 `V+数字`、
`versionCode` 必须等于名字里的编号、必须大于 1、
`BuildConfig.VERSION_NAME` 必须与 properties 一致（否则关于页会骗人）、
关于页不得再写死 `v1.0`。

### 3.14 开机自启卡在「等待 ADB 就绪」（V18 修复）

真机反馈（V17）：服务确实起来了，但一直停在「等待 ADB 就绪」，直到超时也没提权。

**原因**：开机广播触发时 **Wi-Fi 往往还没连上**，此时 mDNS 发现不到
`_adb-tls-connect._tcp`；而原来的总超时只有 90 秒、单次发现只等 15 秒，窗口不够。

**V18 的改动**
1. `AutoBootService.awaitClient()` 先判断网络是否就绪（`ConnectivityManager`
   + `NET_CAPABILITY_INTERNET`），没就绪就每 3 秒轮询、**不计入连接轮次**。
2. 总超时 90s → **300s**；重试间隔 6s → 8s。
3. 前台通知实时显示阶段：「等待网络就绪」/「等待 ADB 就绪（第 N 轮）」。
4. `AdbAutoConnector` 增加快路径：**先读系统属性 `service.adb.tls.port`**，
   拿到端口直接连，跳过 mDNS 十几秒等待；读不到再走 mDNS → 5555。
5. 失败文案给出可操作检查项：Wi-Fi 是否连上、无线调试是否已开、
   网络授权弹窗是否选了「始终允许此网络」。

`BootServiceNetworkWaitTest`（5 条）守住：必须有网络就绪判断、
总超时不得低于 180s、必须更新通知阶段、属性快路径必须在 mDNS 之前、
失败文案必须含「始终允许」。

**未验证**：真机重启后的实际效果（模拟器全掉线）。

### 3.15 配对页卡在「等待 Binder 响应」（V19 修复）

真机反馈：配对流程走到「等待 Binder 响应」就卡住不动。

**原因**：配对命令在 V15 已从 `zt_run --run` 改成只读的 `id`，
但步骤 UI 仍保留着 `waitForService()`——它等的是 **Stellar Binder 服务**，
而那个服务只有 `zt_run` 真正拉起时才存在。命令改了，等待没删，
于是 Binder 永远不出现，界面永远停在「等待」。

**V19 修复**：`id` 输出命中 `uid=` 时**直接调用 `setSuccess()`**，
一次性把所有步骤标记完成并收尾，不再进入任何 Binder 等待。

`PairingNoBinderWaitTest`（3 条）守住：
`uid=` 分支必须含 `setSuccess()`、**不得含 `waitForService()`**、
配对命令必须仍是只读的 `id`（不含 `zt_run`）。

**教训**：改「执行什么」时，必须同时检查「之后在等什么」。
这里命令与等待是耦合的，只改一半就会永久挂起。

### 3.16 提权成功后解锁并打开 KernelSU（V20）

需求：提权完成后自动解锁手机并跳到 root 管理器。

**为什么必须走 root**：普通应用无法解除**安全锁屏**（有密码/图案）。
拿到 uid 0 之后才能用系统命令：

```sh
input keyevent 224        # 唤醒屏幕
wm dismiss-keyguard       # 解除锁屏（root 下有效）
monkey -p <包名> -c android.intent.category.LAUNCHER 1
```

通过载荷建立的 root 通道 `/data/local/tmp/zt_su` 执行，
顺带绕开「后台启动 Activity 被限制」——root shell 发起的 `monkey` 不受该限制。

**实现**（`PostEscalation.kt`）
- 管理器包名按候选匹配，优先 KernelSU 系：
  `me.weishu.kernelsu` → `com.rifsxd.ksunext` → `me.bmax.apatch` → `com.topjohnwu.magisk`
- 用 `monkey` 按包名拉起，不需要知道具体 Activity 类名
- **没装管理器时仍会解锁屏幕**（至少让用户看到屏幕），只是不跳转
- 脚本用 base64 传给设备，避免引号/换行被 shell 拆坏
- 失败只记录，不影响提权结果

**接入点**：两条成功路径都接
1. 手动「执行」成功后 → 日志面板显示「[收尾] …」
2. `AutoBootService` 开机提权成功后 → 通知里带上收尾结果

`PostEscalationTest`（7 条）守住：必须唤醒+解锁、**必须先解锁再拉起**、
用 monkey 按包名 + LAUNCHER category、无管理器时 script 不含 monkey 但仍解锁、
候选包名优先 KernelSU 且不重复、脚本无 CRLF。

**未验证**：真机解锁与跳转（模拟器无 KSU，且全掉线）。

### 3.17 「开机自动提权」的三道门禁（V21）

需求：加 10 秒警告、要求先开无线调试自启、内核不匹配禁止启用。已全部实现。

| # | 门禁 | 表现 |
|---|---|---|
| 1 | **内核不匹配** | 开关直接 `enabled = false`，点不动；副标题写「内核不匹配，此项已禁用」 |
| 2 | **未开无线调试自启** | 点击弹「还需要一步」，提示先开「重启后自动开无线调试」；副标题同步提示 |
| 3 | **10 秒冷静期** | 通过前两关后弹出警告，正文显示「请等待 N 秒后可确认」，**倒计时结束前确认按钮不可点** |

双保险：即便绕过 UI，`AutoBoot.enable()` 内部仍会再判一次 `KernelGate.check().supported`。

`AutoBootGatesTest`（7 条）守住：开关必须有 `enabled = supported`、
必须先判无线调试自启、倒计时从 10 开始并递减、倒计时期间确认按钮 `enabled = warnRemaining <= 0`、
副标题要说明禁用原因、开启后仍以存储为准回读、`AutoBoot.enable` 内部自判内核。

**未验证**：真机上的实际交互（模拟器全掉线）。

### 3.18 删除「安全模式 / 禁用所有模块」开关（V22）

该开关只是 UI 状态（`safeMode`），**从未传给载荷执行**，属于死代码：拨了没有任何实际效果。

已清理：
- `MainActivity` 的状态变量 `safeMode`
- `MainScreen` 的 `safeMode` / `onSafeModeChange` 参数与调用
- 界面上的 `ToggleRow`
- `strings.xml` 里的 `safe_mode_label` / `safe_mode_summary`

全项目 grep `safeMode|safe_mode|禁用所有模块` **无残留**。
`ToggleRow` 组件本身保留（`GhostUi.kt` 里定义，将来可能复用）。

### 3.19 不再要求「接电脑授权」（V23）

真机反馈：点「重启后自动开无线调试（免 root）」弹窗要求用电脑执行
`adb shell pm grant …`，体验很差。

**但根本不需要电脑**：`pm grant` 的原理就是 shell 用户（uid 2000）授予权限，
而本应用配对成功后**本来就持有 ADB shell 通道**——自己给自己授权即可。

**实现**（`SecureSettingsGrant.kt`）
- 通过自家 ADB 通道执行 `pm grant <本应用包名> android.permission.WRITE_SECURE_SETTINGS`
- **以回读权限为准**，不以「命令有没有报错」为准（`pm grant` 成功时无输出）
- 已授权直接短路，不重复执行
- 失败时把电脑端命令作为兜底展示

**两处接入**
1. 设置页点开关且缺权限时 → 后台自动授权 → 成功则继续 `applyNow`
2. 应用启动自愈里也补一次 → 配对完成后开关直接可用

`SecureSettingsGrantTest`（6 条）守住：必须用 `pm grant` + 自己包名、
必须回读确认、已授权短路、设置页自动授权在兜底命令之前、
必须保留兜底命令、启动自愈也要补授权。

**未验证**：真机上的 `pm grant` 是否被 ROM 限制（模拟器全掉线）。

### 3.20 关于页新增作者信息（V24）

关于页在 hero 区下方新增「作者」分组：

| 项 | 值 |
|---|---|
| 作者名 | **Mcjdifif** |
| 点击行为 | 打开酷安主页 `https://www.coolapk.com/u/33270757` |
| 组件 | `SmallTitle` + `Card` + `ArrowPreference`（与「所用开源库」条目风格一致） |

`AboutAuthorTest`（4 条）守住：作者名精确为 `Mcjdifif`、
酷安 URL 完整、点击调用 `uriHandler.openUri(AUTHOR_COOLAPK_URL)`、
关于页必须有「作者」分组。

### 3.21 「明明已连接却提示尚未连接 ADB」（V25 修复）

真机现象：配对成功后，设置页点「重启后自动开无线调试」弹窗仍提示
「尚未连接 ADB」。

**根因**：配对页（StarterScreen）用的是**它自己内部的临时连接**，
`startStellarViaAdb` 里 `client.use { … }` 用完即关，**从不写入 `AdbHolder`**。
而主界面只在启动时跑一次 `AdbAutoConnector`——那时还没配对，所以
`AdbHolder.client` 从头到尾都是 null。

**修复**
1. `popBack()` 检测到「从配对页返回」时，后台主动调一次
   `AdbAutoConnector.connect()` 并写入 `AdbHolder`，日志输出「[连接] 已建立 ADB 连接」
2. 新增 `ensureClient()`：先取现成连接，没有就主动连一次
3. 「本地 ADB」自检前先 `ensureClient()`；仍失败则跳配对页（保留入口）
4. 设置页自动授权前也先尝试连接，而不是直接报「尚未连接」

`AdbHolderAfterPairingTest`（5 条）守住：popBack 必须 `ensureClient()` 且打日志、
必须存在 ensureClient 且内部调用 connector、自检前先确保连接、
设置页授权前先尝试连接、AdbHolder 仍只由连接器统一写入。

### 3.22 ADB 连接改为「单飞 + 可等待 + 有状态」（V26）

V25 只是「配对后补连一次」，真机仍然报「尚未连接 ADB」。继续查发现更深的问题：

**竞态**：后台连接还在跑，界面已经按 `AdbHolder.client == null` 下结论；
而且多处各自调用 `AdbAutoConnector.connect()`，可能同时开多条尝试、状态互相覆盖。

**V26 引入 `AdbConnectionManager`** 作为唯一入口：

| 能力 | 说明 |
|---|---|
| 单飞 | 并发调用共用同一次尝试（`InFlight` + `CountDownLatch`），不会重复连 |
| 可等待 | `awaitClient` / `clientOrConnect` 会**等这次尝试结束**再返回，不提前断言 |
| 重试 | 在总预算内按 2s 间隔重试（`deadline` 控制） |
| 有状态 | `IDLE / CONNECTING / CONNECTED(端口) / FAILED(原因)` |
| 可展示 | `describe()` 直接给界面用，`lastError` 记录最后一轮失败原因 |

**接线**
1. `StarterScreen.setSuccess()`：配对成功**立刻**发起正式连接并落库
2. `MainActivity.popBack()`：从配对页返回时再触发一次（双保险）
3. `ensureClient()` / 设置页授权前：统一走 `clientOrConnect`
4. 设置面板新增只读行「ADB 连接状态」，直接显示 `describe()`

`AdbConnectionManagerTest`（8 条）守住状态机四态、单飞标记与等待、重试预算、
`lastError` 与 `describe()`、配对后必须 `connectBlocking`、设置页必须显示状态、
调用方必须走管理器。

**诊断要点（若仍显示未连接，按顺序查）**
```powershell
adb shell settings get global adb_wifi_enabled      # 是否 1
adb shell getprop service.adb.tls.port              # 无线调试端口
adb logcat -s AdbConn AdbAutoConnector AdbClient    # 每轮尝试与失败原因
adb shell pm list packages | findstr 包名              # 应用是否被停用
```

### 3.23 「运行一会儿就崩溃」= 主线程阻塞（V27 修复）

真机反馈：应用用一会儿就崩溃。

**根因**：V26 把连接统一到 `AdbConnectionManager` 后，设置页那段代码是
在 `SwitchPreference.onCheckedChange`（**主线程**）里直接调
`clientOrConnect(context, 30_000L)`：

```kotlin
onCheckedChange = { want ->
    ...
    val client = AdbConnectionManager.clientOrConnect(context, 30_000L)  // ← 主线程阻塞 30s
```

最长阻塞 30 秒 → **ANR → 系统杀进程**，用户看到的就是「突然崩溃」。

**V27 修复**
1. 连接移进 `thread(isDaemon = true) { … }`，主线程只切状态
2. 界面立即显示「正在连接 ADB…」，不让用户以为没反应
3. 新增 `CrashGuard`：未捕获异常写 `filesDir/crash_last.txt`（含时间/线程/版本/设备/完整堆栈），
   下次启动时把最近 40 行摆到主界面日志面板，用户可直接截图反馈
4. 仍把异常交回原处理器，保持系统崩溃行为（否则会吞异常导致卡死）

`MainThreadNotBlockedTest`（4 条）守住：开关回调里不得出现 `clientOrConnect`、
必须有「正在连接」提示、阻塞方法必须注明只能后台调用、`CrashGuard` 必须已安装且会在启动时展示。

### 3.24 开关不该被「必须先连上 ADB」卡死（V28）

真机反馈：点「重启后自动开无线调试」，打不开。

**根因**：V26/V27 为了让授权走自家 ADB 通道，把这个开关做成了
「没连上就直接 `return@SwitchPreference`」——连开关状态都没保存，
用户看到的就是点不动。

**正确做法**：开关是**设置项**，先存下来；授权在连接可用时再补。
启动自愈本来就会在每次启动补授权，所以「先存后授权」最终一定能授上。

**V28 行为**
1. 点开关 → **立刻保存并置为开启**，界面显示「已开启。正在连接 ADB 并申请所需权限…」
2. 后台尝试连接 + `pm grant`；成功则顺带执行一次 `applyNow`
3. 连不上也不回滚：提示「暂时没有 ADB 连接，权限未授予。开关已保存，连接成功后会自动授予。」
4. 启动自愈继续兜底补授权

`SwitchMustNotRequireConnectionTest`（4 条）守住：`setEnabled` 必须早于连接尝试、
连接失败不能静默退出（要有「开关已保存」说明）、点击要有即时反馈、
启动自愈仍会补授权。

### 3.25 设置面板去掉「关闭」按钮（V29）

设置面板底部原来的蓝色「关闭」按钮已删除，面板更紧凑。

关闭方式保持不变：**点面板外的空白区域**或**按返回键**（`OverlayDialog` 的
`onDismissRequest = onDismiss` 仍生效）。

### 3.26 「点击本地 ADB 没响应」（V30 修复）

真机反馈：点「本地 ADB」毫无反应。

**两个原因叠加**
1. 点击后先调 `ensureClient()` → 内部 `clientOrConnect` **最长阻塞 30 秒**，
   这段时间界面**没有任何变化**
2. `checking` 这个状态虽然设置了，却**从未渲染到界面**——等于没有反馈

**V30 修复**
- 有连接 → 立刻跑自检，结果写日志面板
- 没连接 → **立刻**写日志「当前没有连接，正在进入配对页…」并跳配对页；
  同时在后台尝试重连，结果也会写日志（成功/失败都有）
- 点击路径里不再有 `ensureClient()`，不再阻塞

`ButtonClickFeedbackTest`（5 条）守住：必须立刻写日志、
跳转配对页必须早于任何阻塞连接、点击路径不得出现 `ensureClient()`、
后台连接结果必须上报、只有已连接时才自检。

### 3.27 日志导出（仅 debug）+ 端口扫描兜底（V32）

#### 日志导出
设置面板新增「调试（仅 debug 包可见）」区块（`BuildConfig.DEBUG` 为真才渲染）：

| 入口 | 行为 |
|---|---|
| 复制完整日志 | 写入剪贴板，可直接粘贴发送 |
| 导出并分享日志文件 | 生成 `staticlog-log.txt` 并拉起系统分享 |

导出内容包括：版本、时间、设备型号、Android 版本、内核串、当前连接状态、全部日志行。
日志通过 `AppLog`（上限 800 行）集中存放，`appendLog` 同步写入，
不再依赖参数层层传递。

分享需要 `FileProvider`：已在 Manifest 注册
`${applicationId}.fileprovider`，路径配置在 `res/xml/file_paths.xml`。

#### 端口扫描兜底
真机一直「ADB 未连接」，怀疑是 vivo 的 NSD（mDNS）发现不到无线调试服务。
`AdbAutoConnector` 现在的尝试顺序：

```
系统属性 service.adb.tls.port
  → mDNS 发现 _adb-tls-connect._tcp
  → /proc/net/tcp{,6} 扫本机 LISTEN 端口（回环、最多 12 个）
  → 固定端口 5555
```

扫描只取状态 `0A`（LISTEN）、地址为 `127.0.0.1` / `::1` 的端口，
读不到时也会写一行日志说明，不会静默失败。

`LogExportAndPortScanTest`（6 条）守住：导出入口必须受 `BuildConfig.DEBUG` 限制、
导出内容要含设备/版本/内核/连接上下文、日志有全局缓冲且限长、
FileProvider 已声明且用 applicationId 占位、
必须读 `/proc/net/tcp` 且只认 LISTEN/回环、扫描结果有数量上限、必须写日志说明。

### 3.28 出包流程事故：文件名 V32、包内却是 V31

**事故**：V32 交付里 `StaticLog-V32-debug.apk` 的 `versionCode` 实际是 **31**。
原因：改完 `version.properties` 后只跑了 `:app:assembleRelease`，
`app-debug.apk` 还是上一次（V31）的产物，却被重命名成了 V32。

**修法**：新增 `artifacts/build-release.ps1`，出包流程固定为
1. 从 `version.properties` 读出版本
2. **同时**构建 `assembleDebug` 与 `assembleRelease`
3. 用 `aapt2 dump badging` **逐个校验**产物的 `versionCode` / `versionName`
4. 不一致直接 `exit 1`，不出包
5. 校验通过才复制、改名、输出哈希

用法：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File artifacts\build-release.ps1
```

注意：脚本含中文路径，必须以 **UTF-8 BOM** 保存，否则 PS 5.1 会按 ANSI 解析导致语法错误。

### 3.29 设置面板可滚动 + 一键复制日志（V33）

#### 「调试点不动」
设置面板的根 `Column` **没有滚动容器**。加了「调试」区块后内容超出屏幕，
底部条目落在可视区域之外，点击自然没有命中。

修复：内容改为
```kotlin
Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
```

#### 「点击直接复制当前日志」
调试区块简化为**一个条目**：

| 条目 | 行为 |
|---|---|
| 复制当前日志 | 点一下立即写入剪贴板，Toast 提示「已复制 N 字符」 |

- 不再弹二级弹窗（嵌套 `OverlayDialog` 在设置面板里不可靠）
- 复制内容含版本、时间、设备、系统、内核、连接状态与全部日志行
- 去掉了分享/导出条目，减少一层依赖；`LogExporter.exportToFile` 仍保留备用

`CopyLogOneTapTest`（5 条）守住：面板必须 `verticalScroll` 且有高度上限、
调试条目点击即 `copyToClipboard`、要有 Toast、复制结果不得再弹窗、
调试区块仍仅 debug 可见。

### 3.30 界面说内核匹配、执行却拒（V34 修复）

真机截图：设备卡显示 `6.6.127-android15-8-g24b70dd1cb81-abogki543627741-4k`（完全匹配），
但执行日志是：

```
[门禁] 设备内核：
[失败] 内核不匹配（需要 6.6.127），已拒绝，未写入任何文件
```

内核串是**空的**。

**根因**：`PayloadRunner` 里
```kotlin
val kernel = runCatching { client.runShell("uname -r").trim() }.getOrDefault("")
if (!kernel.contains("6.6.127")) { …内核不匹配… }
```
shell 读取抛异常（连接失效等）→ `getOrDefault("")` 吞成空串 → 判定失败，
却给出了**误导性**的「内核不匹配」。界面走的是 `DeviceInfo`（系统属性），
所以两边结论相反。

**V34 修复**
1. 区分「读失败」与「确实不匹配」，分别打日志：
   `[门禁] shell 读取内核：…` / `[门禁] shell 读取内核失败：…`
2. shell 读不到或为空 → 回退 `DeviceInfo.kernelRelease`（界面用的就是它，已证明可读）
3. 判定统一走 `KernelGate.evaluate`（6.6.127 + 含 7741）
4. 日志打印实际采用的内核串与判定理由

`RunnerKernelGateTest`（6 条）守住：必须区分失败与不匹配、
内核门禁段不得用 `getOrDefault` 吞异常、必须回退 `DeviceInfo.kernelRelease`、
必须走 `KernelGate.evaluate`、必须打印采用的内核串、空串也要回退。

### 3.31 删除内核尾号 7741 校验（V35）

按实际使用要求，**尾号 7741 的校验整体删除**，判定只看主版本 `6.6.127`。

`KernelGate` 相应简化：

| 项 | 改前 | 改后 |
|---|---|---|
| 判据 | 含 `6.6.127` **且** 含 `7741` | 只含 `6.6.127` |
| `EXPECTED_TAIL` | `"7741"` | 已删除 |
| `TailSource` 枚举 | ABOGKI / CONTAINS / GUESSED / NONE | 已删除 |
| `Verdict` 字段 | release / tail / tailOk / prefixOk / tailSource | release / prefixOk |
| 通过条件 | `tailOk && prefixOk` | `prefixOk` |

现在这些串全部放行：

```
6.6.127-android15-8-g24b70dd1cb81-abogki543627741-4k   ✓
6.6.127-android16-8-gabc-abogki7741-4k                 ✓
6.6.127-android16-8-gabc-abogki12345678-4k             ✓（尾号任意）
6.6.127-android16-8-gabc-4k                            ✓（没有尾号）
```

仍然拦截：`5.4.32-*`、`6.6.100-*` 等版本不符的。

`KernelGateTest` 重写为 10 条，钉住「版本对就放行」；`AutoBoot.kt`、`Variant.kt`
里提到「尾号 7741」的注释同步更正（载荷包名 `66127-7741-*` 不变）。

### 3.32 更换启动图标（V36）

用用户提供的线稿（戴毛线帽的幽灵 + 挂锁）替换全部启动图标。

**处理方式**（`artifacts/make_icons.py`）
1. 读入原图，按亮度反相取 alpha：白底 → 透明，黑笔画 → 不透明，
   并把 <14 的杂色压成 0，避免灰底噪点
2. 裁到内容包围盒（1254×1254 → 780×766）
3. 自适应图标：108dp 画布，内容占 **46%** 居中（V37 起，按“白边多加点”再收一档）
   （安全区为 66/108 ≈ 61%；0.60 会压线 → 0.55 贴边 → V37 降到 0.46，白边明显）
4. 生成 5 档密度 × 3 种产物：
   - `ic_launcher_foreground.png`（前景，108/162/216/324/432）
   - `ic_launcher_monochrome.png`（Android 13 主题图标）
   - `ic_launcher.png` / `ic_launcher_round.png`（旧版方形圆角 / 圆形，48dp 满版，内容 0.62 / 0.54）
5. 背景色改为 `#FFFFFF`（与线稿底色一致）

**踩到的坑**：`save()` 里目录名漏了 `mipmap-` 前缀，
图标被写进 `res/mdpi`、`res/xxxhdpi` 这类错误目录，`mipmap-*` 下的旧图标没被动过。
已删掉误建目录并修正为 `mipmap-` + 密度。

**验证**
- `aapt2 dump badging` 显示 `application-icon-* / 640` 全部指向同一个自适应图标
- 从 APK 里抽出 `res/Me.png`（mdpi 前景）与本地文件**逐字节比对一致**
- `res/IG.xml` 反编译确认含 `background / foreground / monochrome` 三项

### 3.33 拔除 KO 探针（V38）

**位置**：主界面控制卡里、变体选择器与「本地 ADB」之间 —— 就是原先
「安全模式 / 禁用所有模块」那一格（那个开关是死代码，早已删除）。

**做什么**：vivo 的 `vr.ko` 在 `__tracepoint_sys_exit` 上挂了回调，凡是**刚拿到
root 凭据**的进程在 exit 时会被它杀掉。拔除方式是把
`__tracepoint_sys_exit->funcs` 置 NULL —— 纯数据写入，不挂钩子、不改代码。
用户态没有内核写接口，所以必须由 ring 0 模块完成这一次写入。

**模块来源（照抄月红 / DirtyFrag VR 上游）**
`yuehong/manager/src/main/jni/dfroot/ko/` 下 8 个 GKI 模块，原样打进
`assets/probe/`（合计 127 KB）：

```
dfroot-android12-5.10.ko   dfroot-android13-5.10.ko
dfroot-android13-5.15.ko   dfroot-android14-5.15.ko
dfroot-android14-6.1.ko    dfroot-android15-6.6.ko   ← 本机命中
dfroot-android16-6.12.ko   dfroot-android17-6.18.ko
```

**KMI 选择**与上游 `select_ko_image` 完全同语义：
先按 `uname -r` 的 `major.minor` + 内核串里的 `androidNN` 精确匹配；
android 对不上时退回**同 major.minor 的第一个**；`major.minor` 不在表里则直接拒绝，
不猜、不盲写。

**配置槽**（与上游 `exp.c` 的扫描逻辑一致）：

| 偏移 | 含义 |
|---|---|
| `[0..7]` | 魔数 `0xDFC0FF16DFC0FF16` |
| `[8..15]` | `cfg[1]` 手工偏移（auto 时 0） |
| `[16..23]` | `cfg[2]` = 1 表示 auto |

auto 模式让模块自己在 ring 0 用运行内核的 BTF 解出 `funcs` 偏移，
不依赖我们猜数值。

**执行流程**（`ProbeRemover.remove`，阻塞，跑在工作线程）
1. 读内核串 → 选模块 → 读 asset
2. 写配置槽（无魔数则拒绝），日志打印 `magic / cfg1 / cfg2`
3. `pushFile` 推到 `/data/local/tmp/zt_dfroot.ko`
4. 用 `zt_su` 校验 `uid=0`，没有就停在这一步并说明
5. root 下 `insmod` + 回读 `insmod_rc` + `dmesg | grep dfroot`
6. 按输出给结论，**认不出来一律不算成功**

**踩到的坑**：第一版把 `cfg[2]=1` 写到 `+23`（最高字节），
测试立刻抓到读回是 `72057594037927936`。小端下 1 应落在 `+16`。
上游是 `memcpy(&automatic, 8)`，等价于写最低字节。

**验证**
- 214 条 JVM 单测全绿，其中 `ProbeRemoverTest` 23 条
- 真实 assets 校验：8 个 ko **逐个**能写入配置槽、魔数保留、长度不变
- 表与 assets 文件名一一对应（防运行期才炸）
- APK 内 `assets/probe/dfroot-android15-6.6.ko` 与上游**逐字节一致**

### 3.34 代码审查后的修复（V39）

外部审查报 12 项（8 Major / 4 Minor），逐条核对后确认 6 项属实并修掉，
其余判为环境噪声或风格问题（见下）。

**#1 「执行」在主线程阻塞（最严重）**
`MainActivity.runPayload()` 原来是先同步调 `AdbAutoConnector.connect(context)`，
而它内部 `latch.await(15, SECONDS)`。onClick 是主线程，15 秒 > 5 秒输入超时 → ANR。
之前只修过「本地 ADB」按钮，这条漏了。
改法：整个「连接 + 执行」搬进 `thread{}`，连接失败时 `main.post` 里再导航到配对页，
并加 `if (running) return` 防重复点击。

**#3 配对客户端未关闭**
`AdbPairingService.kt:330` 是裸 `AdbPairingClient(...).start()`，
流 / Socket / 原生 pairing context 只在 `close()` 里释放。改为 `.use { it.start() }`。

**#9 `checking` 只写不读**
自检状态声明了、赋值了，但界面从不读取，既不显示「进行中」也挡不住连点。
现在：`if (checking)` 做门禁 + 把 `adbChecking` 传进界面渲染「正在自检 ADB…」。

**#10 主界面日志无上限**
`logLines = logLines + line` 每行都复制整个列表且无限增长，`AppLog` 的 800 行上限管不到它。
改为 `(logLines + line).takeLast(LOG_MAX_LINES)`。

**#2 拔探针状态放在 item 内的 remember**
入口在 LazyColumn item 里，滚出屏幕就被销毁，`busy/resultMsg` 随之丢失，
任务还在跑但界面显示没在跑。改为进程级 `ProbeRemoveState`（`mutableStateOf` + object），
并加 busy 时的重复触发保护。

**#11 异常只记 message**
`catch (e: Throwable) { appendLog(e.message) }` 在 message 为空时等于没记。
改为 `类型: message` + `Log.e` 带栈。

**判为无效/不修**
- 审查自报的 `testDebugUnitTest` 失败：是它自定义 `GRADLE_USER_HOME` 导致的
  `AccessDeniedException`，本机同环境 222/222 全绿
- #12「假测试」部分成立：这些是**静态守卫**，已在测试文件头注明不代表行为覆盖，不加戏
- #4/#5/#6/#7（GlobalScope、Socket 泄漏、无超时、长度预分配）属真实但大改，
  不在本次范围；已在交接文档记录待办

**验证**：222 条 JVM 单测全绿，新增 `ReviewRegressionTest` 8 条逐条钉住上述修改
（`runPayload` 的 connect 必须在 `thread{}` 之后、不允许裸 `.start()`、
`checking` 必须被读取、日志必须 `takeLast`、`busy` 不得回到局部 `remember`）。

## 八、快速上手

### 8.1 构建

```powershell
cd ztui
$env:ANDROID_HOME='C:\AndroidSdk'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
& 'C:\gradle\gradle-9.3.1\bin\gradle.bat' assembleDebug   --no-daemon --console=plain
& 'C:\gradle\gradle-9.3.1\bin\gradle.bat' assembleRelease --no-daemon --console=plain
```

### 8.2 跑自动化测试

```powershell
cd ztui
$env:ANDROID_SERIAL='<已连接设备序列号>'
& 'C:\gradle\gradle-9.3.1\bin\gradle.bat' :app:connectedDebugAndroidTest --no-daemon --console=plain
```
覆盖：ADB 连接 / shell / 6MB 同步推送 / 远端执行、`libadb.so` 加载、SPAKE2 上下文、
adb 公钥格式、内核门禁拒绝且零落盘、设备与内存识别、自动选择策略（10 条）、
应用列表权限诊断。

### 8.3 重编 `libadb.so`（一般不需要，源码与产物都在包里）

需要 NDK r27c + boringssl prefab（maven 坐标 `io.github.vvb2060.ndk:boringssl:20250114`）。
四个 ABI 都要编，否则真机（arm64-v8a）会缺库。源码：`ztui/app/src/main/jni/adb_pairing.cpp`。

### 8.4 源码地图

```
ztui/app/src/main/java/com/demo/zt/
├── MainActivity.kt            主界面 / 导航栈(NavDisplay) / 顶栏 / 执行与日志
├── SettingsMenu.kt            齿轮小面板（OverlayDialog + 偏好组件）
├── AboutScreen.kt             关于页（hero + SmallTitle 分组 + 偏好行）
├── KernelGate.kt              内核判定（6.6.127 + 尾号 7741）
├── DeviceInfo.kt              设备 / 芯片 / 内核 / 内存读取
├── Variant.kt                 资源包枚举 + PayloadPolicy（三层依据判定）
├── PayloadResolver.kt         判定落地 + 历史成功记录
├── AutoBoot.kt / BootReceiver.kt   开机自启与 30 次上限
├── AdbAutoConnector.kt        mDNS 发现 + 自动重连
├── AdbHolder.kt               全局 ADB 连接持有者
├── AppContextHolder.kt        Application Context 注入
├── GhostUi.kt                 GhostLock 组件（状态卡 / 信息卡 / 执行按钮 / 日志面板）
├── adb/
│   ├── AdbClient.kt           ADB 协议栈（CNXN/AUTH/OPEN/WRTE/CLSE + sync 推送）
│   ├── AdbKey.kt              RSA 密钥与 adb 公钥编码
│   ├── AdbKeyProvider.kt      **唯一密钥来源**（配对与连接必须同一把）
│   ├── AdbPairingClient.kt    SPAKE2 配对
│   ├── AdbPairingService.kt   配对前台服务（通知 / 输入配对码）
│   ├── AdbMdns.kt             NSD 发现（保留解析出的 host）
│   └── ...
├── shell/
│   ├── PayloadRunner.kt       门禁 → 清理 → 推送 → 权限 → 执行 → 标志解析 → 复核 → 记录
│   ├── PrivilegeEscalator.kt  界面层调用入口
│   └── BundledPayloadCatalog.kt 资源包清单
└── ui/                        月虹移植页面（权限引导 / 五步配对 / 本地 ADB 终端）

ztui/app/src/main/jni/adb_pairing.cpp   SPAKE2 + AES-GCM 的 JNI 桥
ztui/app/src/androidTest/               22 条自动化测试
```
