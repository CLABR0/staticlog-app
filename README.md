# StaticLog

Android 端的设备维护工具。在一台**没有 root** 的手机上，通过系统自带的「无线调试」
建立一条自己的 shell 通道，再执行需要 shell 身份才能完成的维护操作。

包名 `com.demo.zt`，界面用 Miuix + Compose，只支持 Android 8.0（API 26）及以上。

---

## 它解决什么问题

Android 上的应用默认拿不到 shell 身份。要做的事情一旦需要 `adb shell` 权限——
读系统属性、改安全设置、跑需要更高权限的二进制——普通应用就卡在这里。

系统其实留了一个官方口子：**无线调试**（Android 11 起）。配对一次之后，设备会记住
这个客户端的公钥，之后连接不再需要配对码。StaticLog 做的就是：

1. 确保无线调试开关是开的（写 `adb_wifi_enabled`，需要 `WRITE_SECURE_SETTINGS`）
2. 首次由用户输入一次配对码，把本机公钥登记到设备
3. 之后每次开机，用本地保存的私钥直接和 `adbd` 握手，拿到 shell
4. 通过这条通道执行需要 shell 身份的维护任务

配对只需要一次。这是它和「每次都要插电脑」的区别。

---

## 工作方式

```
┌─────────────┐   写设置项    ┌──────────────┐
│  StaticLog  │ ───────────► │ adb_wifi_enabled = 1 │
└─────────────┘               └──────────────┘
       │                              │
       │  mDNS 发现                    ▼
       │  _adb-tls-connect._tcp   ┌─────────┐
       └────────────────────────► │  adbd   │  TLS 监听（端口随机）
                                  └─────────┘
                                       ▲
       ┌─────────────┐   RSA 签名握手    │
       │  本地私钥    │ ─────────────────┘
       └─────────────┘
```

端口来源只有两条：系统属性 `service.adb.tls.port`，和 mDNS 服务发现。
不走 `/proc/net/tcp` 扫描——那条路在应用身份下读不到（见下方「已知边界」）。

---

## 功能

| 功能 | 说明 |
|---|---|
| 无线调试自动恢复 | 开机后自动写回 `adb_wifi_enabled=1`，不再每次手动去开发者选项里点 |
| 一次性配对 | 输一次配对码，之后靠本地私钥自动连接 |
| 自动重连 | 属性端口 → mDNS → 固定端口 `5555`，逐级降级 |
| 开机执行 | 前台服务在后台跑，不依赖界面；有次数上限，可关闭 |
| 机型与载荷匹配 | 按 OriginOS 代数 + 内存规格选对应载荷（`6-12g` / `6-16g` / `7-12g` / `7-16g`） |
| 权限自检引导 | 应用列表、通知、电池优化、厂商自启动四页引导 |
| 日志导出 | debug 包单击复制完整日志，含版本、设备、系统、内核、连接状态 |
| 使用统计（可选） | 上报到自建后端，见 `server/` |

---

## 构建

环境、三步出包、签名和常见错误都在 [BUILD-APK.md](BUILD-APK.md)。

```bash
cd ztui
cp local.properties.example local.properties   # 改成你的 SDK 路径
./gradlew :app:assembleDebug                  # 或 :app:assembleRelease
```

Windows 用 `gradlew.bat`。仓库带完整 Gradle 9.3.1 wrapper，不需要本机装 Gradle。

**工具链**：AGP 9.1.1 / Kotlin 2.3.20 / Gradle 9.3.1 / JDK 21 /
compileSdk 37 / targetSdk 36 / minSdk 26。

release 包需要自己生成一份 keystore，命令在 BUILD-APK.md 的「签名说明」里。
仓库里**没有**任何私钥。

---

## 目录

```
ztui/            Android 工程
  app/src/main/          应用代码与资源
  app/src/main/assets/variants/   按机型分组的载荷
  app/src/test/          JVM 单测（287 条）
  app/src/androidTest/   设备测试
server/          使用统计后端（标准库 Python + systemd + nginx）
docs/            需求清单、变更记录、开发交接
BUILD-APK.md     构建说明
README-PACK.md   包内容说明：哪些东西没带、为什么
```

---

## 已知边界

这些是查过源码确认的，不是猜测：

- **应用身份读不到 `/proc/net/tcp`**。AOSP `private/app.te` 有
  `neverallow { appdomain -shell } proc_net_tcp_udp:file *`，只有 shell/root 域能读。
  所以端口发现不依赖它。
- **无线调试开关会被系统回写 0**。Wi-Fi 未就绪或网络未受信时，
  `AdbDebuggingManager` 会把 `adb_wifi_enabled` 改回 0；`AdbService.systemReady()`
  也会按 `persist.adb.tls_server.enable` 同步一次。开机瞬间补写设置不一定生效，
  要等网络真的就绪。
- **配对凭证分两半**。设备侧公钥在 `/data/misc/adb/adb_keys`（DE 存储，锁屏可读）；
  客户端私钥在应用自己的存储里，必须放在 **DE**（`createDeviceProtectedStorageContext`）
  才能在首次解锁前使用。放 CE 会导致锁屏态读不到，然后静默生成新密钥把旧的覆盖掉。
- **拿到 shell ≠ 拿到 root**。载荷能否在特定启动状态下生效，与能否连上 `adbd` 是两个
  独立问题。

---

## 状态

| 项 | 状态 |
|---|---|
| 构建 | 通过（`assembleDebug` / `assembleRelease` 均出包） |
| JVM 单测 | 287 条全绿 |
| 设备测试 | 31 条，上一轮通过 |
| 真机验证 | 连接与配对路径部分验证；锁屏态自动执行待验证 |

每个版本号等于累计出包次数，记录在 `ztui/version.properties`，变更历史见
[docs/changelog.md](docs/changelog.md)。

---

## 许可与声明

仅供在自己拥有的设备上做维护与调试使用。载荷只针对已适配的内核版本，
不匹配时会拒绝执行。使用者需自行承担在设备上执行维护操作的风险。
