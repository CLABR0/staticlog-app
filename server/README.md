# StaticLog 使用统计后端

一台全新阿里云 ECS 上跑起来，只需要 `python3`。**零第三方依赖**，不用 pip，不用数据库服务。

```
POST /v1/event      客户端上报（install / heartbeat / escalate）
GET  /v1/stats      统计 JSON（需 token）
GET  /dashboard     网页看板（需 ?token=）
GET  /healthz       健康检查
```

数据落一个 SQLite 文件，只存：随机安装 ID、版本号、SDK、机型、内核串、事件计数。
**不存 IP**、不接第三方统计、不采集任何个人标识。

---

## 一、阿里云 ECS 部署

### 0. 先决条件（中国大陆节点必须看）

- ECS 系统建议 **Ubuntu 22.04 / 24.04**
- 安全组放行你要用的端口（下文用 `8080`）
- **80 / 443 绑域名需要 ICP 备案**。没备案就先用 `http://<公网IP>:8080`，
  或用境外节点、或走二级路径反代到你已备案的域名

### 1. 传文件

```bash
# 在你本机执行
scp server/staticlog_stats.py root@<ECS_IP>:/opt/staticlog/
```

### 2. 建服务账号与目录

```bash
ssh root@<ECS_IP>

apt update && apt install -y python3 sqlite3 curl
useradd -r -s /usr/sbin/nologin staticlog || true
mkdir -p /opt/staticlog
chown -R staticlog:staticlog /opt/staticlog
```

### 3. 生成 token

```bash
python3 -c "import secrets;print(secrets.token_urlsafe(32))"
# 例：Xk3j9Qm2pR7vT1sW5yZ8bN4cD6fG0hJ2kL5mN8pQ1rS
```

把它记下来。**这个 token 就是看板密码**，别贴到公开的地方。

### 4. 装成 systemd 服务

```bash
cat >/etc/systemd/system/staticlog-stats.service <<'UNIT'
[Unit]
Description=StaticLog usage stats
After=network.target

[Service]
Type=simple
User=staticlog
WorkingDirectory=/opt/staticlog
Environment=PORT=8080
Environment=DB=/opt/staticlog/stats.db
Environment=STATS_TOKEN=REPLACE_WITH_YOUR_TOKEN
ExecStart=/usr/bin/python3 /opt/staticlog/staticlog_stats.py
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now staticlog-stats
systemctl status staticlog-stats --no-pager
```

把 `REPLACE_WITH_YOUR_TOKEN` 换成第 3 步生成的值。

### 5. 验证

```bash
curl http://127.0.0.1:8080/healthz
# {"ok": true, "service": "staticlog-stats"}

curl -H 'Content-Type: application/json' \
     -d '{"install_id":"11111111-2222-3333-4444-555555555555","event":"install","version":"V40","sdk":"36","model":"TEST","kernel":"6.6.127"}' \
     http://127.0.0.1:8080/v1/event
# {"ok": true}

curl "http://127.0.0.1:8080/v1/stats?token=<你的token>" | head -c 400
```

浏览器打开看板：

```
http://<ECS公网IP>:8080/dashboard?token=<你的token>
```

### 6. 安全组

阿里云控制台 → 实例 → 安全组 → 入方向，加一条：

| 协议 | 端口 | 授权对象 |
|---|---|---|
| TCP | 8080 | 0.0.0.0/0 |

正式环境建议只放行反向代理端口，见下节。

---

## 二、（可选）用 Nginx + 域名 + HTTPS

```bash
apt install -y nginx certbot python3-certbot-nginx
```

`/etc/nginx/sites-available/staticlog`：

```nginx
server {
    listen 80;
    server_name stats.example.com;

    # 只暴露必要路径，其余一律 404
    location /v1/   { proxy_pass http://127.0.0.1:8080; proxy_set_header X-Forwarded-For $remote_addr; }
    location /healthz { proxy_pass http://127.0.0.1:8080; }
    location /dashboard { proxy_pass http://127.0.0.1:8080; }
    location / { return 404; }

    client_max_body_size 16k;
}
```

```bash
ln -s /etc/nginx/sites-available/staticlog /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
certbot --nginx -d stats.example.com      # 自动配 HTTPS
```

配好后地址是 `https://stats.example.com`，客户端就填这个。

---

## 三、接入客户端

**不用改代码。** 在 `ztui/gradle.properties` 加一行：

```properties
staticlog.statsEndpoint=https://stats.example.com
```

然后重新出包。这个值会写进 `BuildConfig.STATS_ENDPOINT`：

- 留空（默认）→ 上报路径在 `isEnabled()` 处直接 return，**一个请求都不发**
- 非空 → 设置页出现「隐私 → 匿名使用统计」开关，默认开启，可随时关闭

### 客户端发什么

```json
{
  "install_id": "随机 UUID，首次运行生成",
  "event": "install | heartbeat | escalate",
  "version": "V41",
  "sdk": "36",
  "model": "V2408A",
  "kernel": "6.6.127-android15-..."
}
```

不发：手机号、账号、IMEI、MAC、ANDROID_ID、位置、联系人、应用列表、文件内容。

### 触发时机

| 事件 | 时机 |
|---|---|
| `install` | 首次运行 |
| `heartbeat` | 之后每次启动 |
| `escalate` | 每次提权成功 |

---

## 四、日常运维

```bash
# 看实时日志
journalctl -u staticlog-stats -f

# 备份数据库（WAL 模式下用 backup 命令，别直接 cp）
sqlite3 /opt/staticlog/stats.db ".backup '/root/stats-$(date +%F).db'"

# 看有多少设备
sqlite3 /opt/staticlog/stats.db "SELECT COUNT(*) FROM devices;"

# 看今天活跃
sqlite3 /opt/staticlog/stats.db "SELECT COUNT(*) FROM devices WHERE last_seen >= strftime('%s','now')-86400;"

# 重置全部数据
systemctl stop staticlog-stats
rm -f /opt/staticlog/stats.db*
systemctl start staticlog-stats
```

### 限流与容量

- 每 IP 60 秒 120 次写请求（内存计数，重启清零）
- 单条 body 上限 4 KB
- 事件名白名单，字段长度截断
- SQLite + WAL，万级设备无压力；十万级再换 PostgreSQL

---

## 五、接口细节

### `POST /v1/event`

| 字段 | 必填 | 说明 |
|---|---|---|
| `install_id` | ✅ | 8–64 位字母数字或 `-` |
| `event` | ✅ | `install` / `heartbeat` / `escalate` |
| `version` `sdk` `model` `kernel` | — | 超长自动截断 |

返回 `200 {"ok":true}` / `400` / `429`。

### `GET /v1/stats?token=...&days=30`

```json
{
  "generated_at": "2026-11-04 05:10:00 UTC",
  "totals": {"installs": 3, "active_24h": 3, "active_7d": 3, "active_30d": 3, "escalations": 1},
  "versions": [{"version": "V40", "devices": 2}],
  "models":   [{"model": "V2408A", "devices": 2}],
  "sdks":     [{"sdk": "36", "devices": 2}],
  "events":   {"install": 2, "heartbeat": 1, "escalate": 1},
  "daily":    [{"day": "2026-11-04", "active": 3, "new": 3}]
}
```

### 看板

`/dashboard?token=...` 单文件 HTML，无 CDN 依赖，离线也能渲染：
五个数字卡（总安装 / 24h / 7d / 30d / 提权成功）+ 30 天柱状图 + 版本、机型、Android 版本分布表。

---

## 六、本地自测

```bash
STATS_TOKEN=dev PORT=18080 DB=./test.db python3 staticlog_stats.py &

curl -s localhost:18080/healthz
curl -s -X POST localhost:18080/v1/event -H 'Content-Type: application/json' \
  -d '{"install_id":"aaaaaaaa-1111-2222-3333-444444444444","event":"install","version":"V40","sdk":"36","model":"M","kernel":"k"}'
curl -s "localhost:18080/v1/stats?token=dev"
open "localhost:18080/dashboard?token=dev"
```

---

## 七、没做的事

- **没有用户账号体系**：看板靠一个 token，够用且简单
- **没有留存率/漏斗**：只有设备数、活跃、版本分布。要更细就加事件
- **IP 不入库**：所以做不到地域统计。需要的话得先想清楚合规
- **没做 TLS**：交给 Nginx / certbot，不自己搓
