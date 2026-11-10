# 变更记录 — 基于 CVE-2026-64560

版本号 = 累计出包次数，不是语义化版本。早期版本（V1–V32）的包哈希记在
`version.properties` 头部，细节已并入 [handoff.md](handoff.md)。下面从 V33 起按时间倒序列出。

---

## V47 — ADB 私钥搬进 DE：让锁屏态有机会连上

- **根因**：`AdbKeyProvider` 用 `getSharedPreferences(..., MODE_PRIVATE)`，密文落在 **CE 存储**
  （`/data/data/<pkg>/shared_prefs/adb_keys`），首次解锁前读不到。而 `AdbKey.getOrCreatePrivateKey()`
  在读到 null 时会**当场生成新 RSA 密钥并写回** —— 等于锁屏态每次启动都把原来那把已被 adbd
  授权的密钥覆盖掉，之后解锁也连不上。
- **迁移**：新增 `AdbKeyMigration`，解锁后把 CE 里的密文**原样复制**到 DE
  （`createDeviceProtectedStorageContext()`），幂等、`commit()` 同步写、写后读回比对。
  不重新生成、不重新配对。解锁前调用会自己跳过。
  调用点：`BootReceiver`（开机广播）+ `MainActivity.onCreate`（兜底）。
- **禁止覆盖**：`AdbKey` 新增 `allowGenerate` 参数。锁屏路径走
  `AdbKeyProvider.adbKeyForBootOrNull()`，DE 里没有可用密文时**只放弃连接、不生成新密钥**，
  并写 `BootTrace` 说明原因。
- **删掉死代码**：`AdbAutoConnector` 里读 `/proc/net/tcp` 找端口的实现已删除。
  AOSP `private/app.te` 有 `neverallow { appdomain -shell } proc_net_tcp_udp:file *`，
  普通应用域永远读不到该文件，解锁后也一样，留着只是白等。
  端口发现现在只剩两条路：`service.adb.tls.port` 属性、mDNS `_adb-tls-connect._tcp`。
- **未验证**：锁屏态下 AndroidKeyStore 的 AES 包裹密钥能否解密（默认可以，
  但若当初设过 `setUserAuthenticationRequired(true)` 会失败）。
  日志会区分「DE 无密文」和「有密文但解密失败」两种情况，重启后看 `boot_trace.log` 即可。
- 单测 287 条全绿（新增 13 条覆盖迁移的幂等、覆盖保护、跳过与失败分支）。

## V46 — 开机轨迹落盘 + 解锁前先真试一次

- **问题**：用户解锁后才看到通知，但 `AppLog` 只在内存里，开机那段日志随进程回收就没了，
  无法判断"解锁前到底走到哪一步"。这也让"能不能不解锁就提权"只能靠猜。
- **新增 `BootTrace`**：每步写入 **DE 存储**的 `boot_trace.log`，
  格式 `MM-dd HH:mm:ss [+开机秒数 未解锁/已解锁] 步骤`，上限 64 KB 自动截断。
  应用启动时并入界面日志（前缀 `[开机]`）。
- **解锁前先真试一次**：`awaitClient` 增加"第零段"——未解锁时立即尝试连接一次，
  成功就直接跑（不再等解锁），失败则记录失败原因再进入等解锁循环。
  这样"平台到底拦不拦"有实测依据。
- 记录点：收到哪条广播、是否同一次开机已启动、开关状态、服务启动、前台通知已发布、
  解锁前试连结果、进入等解锁循环、超时放弃。
## V45 — 管理器门禁：读不到列表不再拦死

- **问题**：门禁只跑一条 `pm list packages`，命令报错或输出为空就当作"读不到"，
  直接拒绝执行 —— 在 `pm` 抽风的机器上整条提权链路被拦死，用户看到
  「无法读取已安装应用列表」但明明装了管理器。
- **修法一：多条命令兜底**。依次尝试 `pm list packages` →
  `cmd package list packages` → `pm list packages -3`，每条重试 2 次，
  任一读到 `package:` 行即采用。
- **修法二：区分"没装"和"没读到"**。
  - 读到了但缺管理器 → **拒绝执行**（保持原设计，这是用户要求）
  - 读不到 / 输出无 `package:` 行 → **放行，但标记未确认**，
    日志写「⚠ 未能确认管理器，按已安装继续（结果见下方复核）」
  理由：缺管理器时载荷自己会在复核阶段失败，硬拦的代价是把可用环境一起挡死。
- 新增 `RootManagerGate.probe()`：返回命令名、尝试次数、输出字节数、前 3 行，
  一行写进日志，出问题能直接看出是哪条命令、什么错。
## V44 — 同一开机只起一次自动提权

- 解锁前后会分别收到 `LOCKED_BOOT_COMPLETED` 与 `BOOT_COMPLETED`，此前两处都会
  起服务，后果是**计数 +2**（30 次上限提前耗尽）、两个服务互删
  `/data/local/tmp/zt*` 造成偶发失败。
- 新增 `AutoBoot.beginSessionIfNeeded`：用 DE 存储里的 30 分钟窗口判定"同一开机"，
  第二次广播直接跳过。
- 解锁后的那次广播仍会补写 `adb_wifi_enabled`（对 ROM 在 `systemReady` 里覆盖开关的情况有效），
  只是不再起第二个服务。
## V43 — 开机自动提权改为「解锁即跑」

- 根因：ADB 密钥在 CE 存储，解锁前 adbd 无法认证；且 `BOOT_COMPLETED` 在解锁后才发，
  旧 receiver 又没声明 `directBootAware`，所以整条链路必然等到解锁后才启动。
- `BootReceiver` / `AutoBootService` 加 `android:directBootAware="true"`，
  解锁前即收到 `LOCKED_BOOT_COMPLETED`。
- `AutoBoot` 的开关与计数改存 **DE 存储**，并带一次性迁移，
  升级用户不会出现"开关自己变回关闭"。
- 新增 `UnlockWait`：等解锁最长 10 分钟、1 秒轮询；解锁瞬间立即进入连接阶段
  （5 分钟 / 8 秒一轮）。此前是直接开连，300 秒窗口在用户解锁前就耗尽了。
- 等待期间每秒补写一次 `adb_wifi_enabled`（幂等），避免系统在解锁时重置该开关。
## V42 — 橘子7 换载荷 + root 管理器门禁

- **橘子7（7-12g / 7-16g）载荷换成 A17 SukiSU 包**：
  `zt_prep.zte` 6,222,474 B → 4,892,198 B，`zt_fanout.zte` / `zt_run` 同步替换。
  新包对应 **SukiSU Ultra** 管理器（上游脚本写的是 v4.3.0-e67b14e6@tadaki）。
  橘子6 的六个文件一字节未动，单测按 SHA-256 逐文件校验。
- **新增 root 管理器门禁** `RootManagerGate`，位置在内核门禁之后、推文件之前：
  - 橘子6（V6）→ 需 `me.weishu.kernelsu` / `com.resukisu.resukisu` / `com.rifsxd.ksunext`
  - 橘子7（V7）→ 需 `com.sukisu.ultra`（SukiSU Ultra）
  
  缺管理器直接拒绝并写明缺什么，**不推送、不 insmod**；
  `pm list packages` 读取失败时报「无法确认」，与「没装」分开处理。
- 依据：橘子6 官方脚本靠 `/data/app/**/libksud.so` late-load 激活 KernelSU，
  没有对应管理器就没有 late-load 目标；橘子7 新包成功后由 SukiSU Ultra 授权。

## V40 — 关于页 GitHub 入口 + 自动提权留痕

- **关于页新增「GitHub 开源库」**：分组「本项目」下，点击打开
  github.com/zyyihao5-lang/staticlog。
- **开机自动提权写入 AppLog**：此前 AutoBootService 只写 logcat，
  「复制日志」里看不到自动流程的痕迹，用户会以为"凭空提权了"。
  现在服务启动即写 [自动提权] 开机广播触发，开始执行（计数 N/30），
  等待 / 重试 / 成功 / 失败全部带前缀进入导出日志。
- 排查结论记录：全工程唯一的自动执行入口是 BootReceiver → AutoBootService；
  EXTRA_AUTO_RUN 只有声明与读取、无任何写入点，配对页也不调用 PayloadRunner。

## 文档更新（无版本号变更）

- **把「全自动提权」写成一等功能**：新增 [auto-escalation.md](auto-escalation.md)，
  README 重构为以自动提权开篇（开机无人值守 + 手动一键两条路、三道闸、关键参数）。
- 全部文档 H1 统一带上 CVE-2026-64560。

- 补充 **CVE-2026-64560** 归属：新增 [cve-2026-64560.md](cve-2026-64560.md)，
  README 增加「漏洞基础」小节，architecture 补载荷血统。
  证据分级写清楚：框架包含该后端（确认）、载荷具体后端（二进制无证据，标注为推断）。
- 仓库转为公开。

## V39 — 代码审查后的修复

外部审查报 12 项，逐条核对后确认 6 项属实并修复。

- **修复 ANR**：`runPayload()` 原来在 `onClick`（主线程）里同步调用
  `AdbAutoConnector.connect()`，内部 `latch.await(15s)`，超过 5 秒输入超时就 ANR。
  整个「连接 + 执行」搬进工作线程，失败回主线程导航；加 `if (running) return` 防重入。
- **修复资源泄漏**：`AdbPairingService` 里 `AdbPairingClient(...).start()` 未关闭，
  改为 `.use { it.start() }`。
- **修复无效状态**：`checking` 只写不读，既不显示"自检中"也挡不住连点。
  现在做门禁 + 界面渲染「正在自检 ADB…」。
- **修复日志无上限**：主界面 `logLines` 每行复制整个列表且无限增长，改 `takeLast(800)`。
- **修复状态丢失**：拔探针的 `busy/result` 原本在 LazyColumn item 的 `remember` 里，
  滚出屏幕即丢失；提升为进程级 `ProbeRemoveState`。
- **修复异常信息丢失**：只记 `e.message` 改为 `类型: message` + `Log.e` 带栈。
- 新增 `ReviewRegressionTest` 8 条静态守卫，防止上述写法回退。

未修（记录待办）：Service 级 `GlobalScope`、`AdbClient` 失败时的 Socket 清理、
流操作缺超时、协议头长度未校验就预分配。

## V38 — 拔除 KO 探针

- 新增主界面入口「拔除 KO 探针」，位置在原「安全模式」开关处（那个开关是死代码，已删）。
- 内置 8 个 GKI 内核模块（`assets/probe/`，127 KB），按 `major.minor` + `androidNN` 选择，
  与上游 `select_ko_image` 同语义：精确匹配 → 同版本兜底 → 无匹配则拒绝。
- 配置槽写入 auto 模式（`cfg[2]=1`），由模块在 ring 0 用 BTF 自解偏移。
- 执行链：push 模块 → 校验 `uid=0` → `insmod` → 回读 rc 与 `dmesg` → 按输出判定。
  认不出来一律不算成功。
- 修复小端写入错误（`cfg[2]` 应在 `+16`，第一版写到 `+23`）。

## V37 — 图标白边加大

- 自适应内容占比 `0.55 → 0.46`，旧版方形 `0.74 → 0.62`、圆形 `0.64 → 0.54`。

## V36 — 更换启动图标

- 用线稿（戴毛线帽的幽灵 + 挂锁）替换全部启动图标：白底转透明、裁包围盒、
  生成自适应前景 / 单色 / 旧版方与圆共 5 档密度。
- 背景色改 `#FFFFFF`。
- 踩坑：`save()` 目录名漏 `mipmap-` 前缀，图标写进了 `res/mdpi` 之类的错误目录。

## V35 — 删除内核尾号校验

- 判定只看 `6.6.127`，删除 `7741` 尾号校验与 `TailSource` 枚举。
- `KernelGateTest` 重写为 10 条，钉住「版本对就放行」。

## V34 — 修复"界面说匹配、执行却拒"

- 根因：`PayloadRunner` 用 `runShell("uname -r").getOrDefault("")`，
  shell 读失败被吞成空串，于是报「内核不匹配」——把读失败说成了不匹配。
- 区分「读失败」与「确实不匹配」，失败回退 `DeviceInfo.kernelRelease`，
  判定统一走 `KernelGate.evaluate`，日志打印实际采用的内核串。

## V33 — 日志与设置面板

- 「复制当前日志」改为**点一下直接复制**，不再多一步确认。
- 设置面板支持滚动（此前条目超出屏幕导致「点不动」）。
- 修复「文件名 V32 / 包内 V31」：改用 `build-release.ps1` 同时构建并逐包校验版本。

---

## 更早

V1–V32 的构建哈希见 `version.properties` 头部。功能演进（ADB 自实现、配对、
自动连接、开机自启、内核门禁、提权收尾等）在 [handoff.md](handoff.md) 中按小节记录。
