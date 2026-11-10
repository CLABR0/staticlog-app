# V47 发版说明

发布日期：2026-11-10
版本号：`versionCode 47` / `versionName V47`

---

## 一句话

修掉一个会让「重启后不输密码也自动执行」永久失效的缺陷：ADB 私钥被存在了
首次解锁前读不到的地方，而且读不到时还会**静默换掉**原来那把已经被设备信任的密钥。

---

## 这次改了什么

### 1. 私钥搬进设备加密存储（DE）

**改之前**：`AdbKeyProvider` 用 `getSharedPreferences(..., MODE_PRIVATE)`，密文落在
`/data/data/com.demo.zt/shared_prefs/adb_keys` —— 属于 CE（凭证加密）存储，
用户首次解锁前整个目录不可访问。

**改之后**：改用 `createDeviceProtectedStorageContext()`，密文落在
`/data/user_de/0/com.demo.zt/shared_prefs/adb_keys`，锁屏态可读。

新增 `AdbKeyMigration`：解锁后把 CE 里的密文**原样复制**到 DE。复制的是 Base64
密文字符串，不是重新生成密钥，所以**不需要重新配对**。迁移幂等，`commit()` 同步写，
写完读回比对。解锁前调用会自己跳过。

调用点两处：`BootReceiver`（开机广播）、`MainActivity.onCreate`（兜底）。

### 2. 锁屏时禁止生成新密钥

**改之前**：`getOrCreatePrivateKey()` 读不到密文就当场生成一把新 RSA 密钥并写回。
在锁屏态这意味着每次开机都把那把**已被 adbd 授权**的密钥覆盖掉——不但这次连不上，
解锁后也连不上。

**改之后**：`AdbKey` 增加 `allowGenerate` 参数。锁屏路径走
`AdbKeyProvider.adbKeyForBootOrNull()`，DE 里没有可用密文时**只放弃连接**，
并写一条 `BootTrace` 说明原因。

日志能把两种失败分开：

| 日志 | 含义 |
|---|---|
| `锁屏态：DE 里没有 ADB 私钥，放弃本次连接` | 没迁过，或者从没配对过 |
| `锁屏态：ADB 私钥读取失败，放弃本次连接：…` | 密文在，但解密失败（见下方「待验证」） |

### 3. 删掉读不到的端口扫描

`AdbAutoConnector` 里原来有一段读 `/proc/net/tcp` 找 LISTEN 端口的兜底逻辑。
AOSP `system/sepolicy/private/app.te` 第 717 行：

```
neverallow { appdomain -shell } proc_net_tcp_udp:file *;
```

`neverallow` 是编译期硬约束，普通应用域对该文件的任何操作都被禁止——**解锁后也读不到**。
这段代码在任何状态下都只返回空列表，留着只是每次连接白等十几秒。已删除，并在原位置
写了注释说明原因。

端口发现现在只剩两条路：`service.adb.tls.port` 系统属性、mDNS `_adb-tls-connect._tcp`。

---

## 验证记录

| 项 | 结果 |
|---|---|
| 编译 | `BUILD SUCCESSFUL` |
| JVM 单测 | **287 条全绿**（新增 13 条覆盖迁移：幂等、覆盖保护、跳过、提交失败、读回不一致） |
| release 签名 | 与 V46 同一张证书 `d4d2421c…`，可直接覆盖安装 |
| `versionCode` / `versionName` | `47` / `V47`（`aapt2 dump badging` 核对） |
| 真机锁屏路径 | **未验证** |

单测新增覆盖的五个分支：

1. DE 已有密文 → 不覆盖、返回 true
2. 未解锁 → 返回 false、不写 DE
3. CE 有密文、DE 没有 → 复制成功、内容逐字节相同
4. CE 也没有 → 返回 false
5. `hasDeviceProtectedKey` 在 DE 有/无两种情况下分别正确

---

## 怎么验证锁屏路径

迁移必须在**解锁状态**才能做，所以顺序不能反：

1. 安装 V47（可覆盖 V46，签名相同）
2. **正常解锁用一次**，让迁移跑完
3. 去日志里确认出现 `ADB 私钥已复制到 DE（锁屏可读）`
4. 重启，**这次不要解锁**，等一分钟
5. 读 `boot_trace.log`（DE 存储，锁屏可读）

第 5 步的日志会落在下面几种情况之一：

| 日志 | 结论 |
|---|---|
| `DE 里没有 ADB 私钥` | 第 3 步没成功，回去看迁移日志 |
| `ADB 私钥读取失败：…` | 密文在，但 AndroidKeyStore 的 AES 包裹密钥解不开 |
| `跳过 /proc/net/tcp 扫描` + `未发现无线调试服务` | 连不上因为 adbd 没起监听：查 `adb_wifi_enabled` 是否被回写、Wi-Fi 是否受信 |
| `已连接，身份 uid=…` | 连上了，接下来看载荷能否在该状态下执行 |

---

## 待验证

**锁屏态下 AndroidKeyStore 的 AES 包裹密钥能否解密。**

`AdbKey` 用一把 AndroidKeyStore 里的 AES 密钥包裹 RSA 私钥。该密钥默认
`setUnlockedDeviceRequired(false)`、`setUserAuthenticationRequired(false)`，
理论上锁屏可用。但如果当初生成时被设过 `setUserAuthenticationRequired(true)`，
锁屏解密会抛异常，日志里会显示 `ADB 私钥读取失败`。

真出现这种情况的解法是换一把新密钥并重新配对一次，而不是继续搬密文。

---

## 兼容性

- 覆盖安装：可以（同签名，`versionCode 47 > 46`）
- 从 V46 升级：首次解锁后自动迁移，不需要重新配对
- 全新安装：需要配对一次，和以前一样
- 数据清空/卸载重装：DE 和 CE 一起清，需要重新配对

---

## 文件

| 文件 | 大小 | sha256 |
|---|---|---|
| `StaticLog-V47-release.apk` | 45.71 MB | `BFD6EE2F0E7E23000FF9FCDDC39AEE12382B28CDD950E408795E81220B8AE98C` |
| `StaticLog-V47-debug.apk` | 53.03 MB | `D0EE8B550C7BE6D9A3F1948F7BB21D37B6081A003988501F1585A2F60F4FE3BF` |
