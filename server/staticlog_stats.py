#!/usr/bin/env python3
"""
StaticLog 使用统计后端。

设计目标：一台全新 ECS 上 `python3 staticlog_stats.py` 就能跑，不装任何第三方包。
只用标准库：http.server + sqlite3 + json + threading。

接口
    POST /v1/event        客户端上报（install / heartbeat / escalate）
    GET  /v1/stats        统计 JSON（需 Bearer token）
    GET  /dashboard       网页看板（需 ?token=）
    GET  /healthz         健康检查（无鉴权）

数据
    SQLite 单文件。只存：随机安装 ID、版本号、SDK、机型、内核串、事件。
    不存 IP、不存 IMEI/序列号/手机号/MAC，不接任何第三方 SDK。

启动
    STATS_TOKEN=换成你的长随机串 python3 staticlog_stats.py
    # 可选：PORT=8080 DB=./stats.db
"""

import json
import os
import re
import sqlite3
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("PORT", "8080"))
DB_PATH = os.environ.get("DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "stats.db"))
ADMIN_TOKEN = os.environ.get("STATS_TOKEN", "").strip()

# 允许的事件名白名单：客户端拼错/被改也不写脏数据
ALLOWED_EVENTS = {"install", "heartbeat", "escalate"}

# 字段长度上限，防止有人往库里塞大字符串
FIELD_LIMITS = {
    "install_id": 64,
    "version": 24,
    "sdk": 8,
    "model": 64,
    "kernel": 128,
    "event": 16,
}

INSTALL_ID_RE = re.compile(r"^[A-Za-z0-9\-]{8,64}$")

# 简易限流：每 IP 60 秒内最多 120 次写请求
RATE_WINDOW = 60
RATE_MAX = 120
_rate_lock = threading.Lock()
_rate: dict[str, list[float]] = {}


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def day_key(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%d")


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def init_db() -> None:
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (
                install_id  TEXT PRIMARY KEY,
                first_seen  INTEGER NOT NULL,
                last_seen   INTEGER NOT NULL,
                last_version TEXT,
                sdk         TEXT,
                model       TEXT,
                kernel      TEXT,
                events      INTEGER NOT NULL DEFAULT 0,
                escalations INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS daily (
                day        TEXT NOT NULL,
                install_id TEXT NOT NULL,
                PRIMARY KEY (day, install_id)
            );
            CREATE TABLE IF NOT EXISTS events (
                event TEXT NOT NULL,
                day   TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (event, day)
            );
            CREATE INDEX IF NOT EXISTS idx_devices_last ON devices(last_seen);
            """
        )


def rate_ok(ip: str) -> bool:
    now = time.time()
    with _rate_lock:
        bucket = _rate.setdefault(ip, [])
        bucket[:] = [t for t in bucket if now - t < RATE_WINDOW]
        if len(bucket) >= RATE_MAX:
            return False
        bucket.append(now)
        # 顺手清理长期不活跃的 IP，避免内存涨
        if len(_rate) > 5000:
            for k in [k for k, v in _rate.items() if not v]:
                _rate.pop(k, None)
        return True


def clean(value, field: str) -> str:
    if value is None:
        return ""
    text = str(value)[: FIELD_LIMITS.get(field, 64)]
    # 控制字符一律去掉，防止日志注入
    return "".join(ch for ch in text if ch.isprintable()).strip()


def record_event(payload: dict) -> tuple[int, dict]:
    install_id = clean(payload.get("install_id"), "install_id")
    event = clean(payload.get("event"), "event")
    version = clean(payload.get("version"), "version")
    sdk = clean(payload.get("sdk"), "sdk")
    model = clean(payload.get("model"), "model")
    kernel = clean(payload.get("kernel"), "kernel")

    if not INSTALL_ID_RE.match(install_id):
        return 400, {"error": "install_id 不合法"}
    if event not in ALLOWED_EVENTS:
        return 400, {"error": "event 不在白名单"}

    ts = int(time.time())
    day = day_key(ts)
    with db() as conn:
        row = conn.execute(
            "SELECT install_id FROM devices WHERE install_id = ?", (install_id,)
        ).fetchone()
        if row is None:
            conn.execute(
                "INSERT INTO devices (install_id, first_seen, last_seen, last_version, sdk, model, kernel, events) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                (install_id, ts, ts, version, sdk, model, kernel),
            )
        else:
            conn.execute(
                "UPDATE devices SET last_seen = ?, last_version = COALESCE(NULLIF(?, ''), last_version), "
                "sdk = COALESCE(NULLIF(?, ''), sdk), model = COALESCE(NULLIF(?, ''), model), "
                "kernel = COALESCE(NULLIF(?, ''), kernel), events = events + 1 "
                "WHERE install_id = ?",
                (ts, version, sdk, model, kernel, install_id),
            )
        conn.execute(
            "INSERT OR IGNORE INTO daily (day, install_id) VALUES (?, ?)", (day, install_id)
        )
        conn.execute(
            "INSERT INTO events (event, day, count) VALUES (?, ?, 1) "
            "ON CONFLICT(event, day) DO UPDATE SET count = count + 1",
            (event, day),
        )
        if event == "escalate":
            conn.execute(
                "UPDATE devices SET escalations = escalations + 1 WHERE install_id = ?",
                (install_id,),
            )
    return 200, {"ok": True}


def build_stats(days: int = 30) -> dict:
    now = int(time.time())
    days = max(1, min(days, 365))
    with db() as conn:
        total = conn.execute("SELECT COUNT(*) AS c FROM devices").fetchone()["c"]
        active_1 = conn.execute(
            "SELECT COUNT(*) AS c FROM devices WHERE last_seen >= ?", (now - 86400,)
        ).fetchone()["c"]
        active_7 = conn.execute(
            "SELECT COUNT(*) AS c FROM devices WHERE last_seen >= ?", (now - 7 * 86400,)
        ).fetchone()["c"]
        active_30 = conn.execute(
            "SELECT COUNT(*) AS c FROM devices WHERE last_seen >= ?", (now - 30 * 86400,)
        ).fetchone()["c"]
        total_esc = conn.execute("SELECT COALESCE(SUM(escalations), 0) AS c FROM devices").fetchone()["c"]
        versions = conn.execute(
            "SELECT COALESCE(NULLIF(last_version, ''), '未知') AS v, COUNT(*) AS c "
            "FROM devices GROUP BY v ORDER BY c DESC LIMIT 20"
        ).fetchall()
        models = conn.execute(
            "SELECT COALESCE(NULLIF(model, ''), '未知') AS m, COUNT(*) AS c "
            "FROM devices GROUP BY m ORDER BY c DESC LIMIT 15"
        ).fetchall()
        sdks = conn.execute(
            "SELECT COALESCE(NULLIF(sdk, ''), '未知') AS s, COUNT(*) AS c "
            "FROM devices GROUP BY s ORDER BY c DESC LIMIT 15"
        ).fetchall()
        events = conn.execute(
            "SELECT event, SUM(count) AS c FROM events GROUP BY event"
        ).fetchall()
        daily_rows = conn.execute(
            "SELECT day, COUNT(*) AS active FROM daily WHERE day >= ? GROUP BY day ORDER BY day",
            (day_key(now - (days - 1) * 86400),),
        ).fetchall()
        new_rows = conn.execute(
            "SELECT day, COUNT(*) AS new FROM ("
            "  SELECT install_id, MIN(day) AS day FROM daily GROUP BY install_id"
            ") WHERE day >= ? GROUP BY day ORDER BY day",
            (day_key(now - (days - 1) * 86400),),
        ).fetchall()

    by_day = {r["day"]: {"day": r["day"], "active": r["active"], "new": 0} for r in daily_rows}
    for r in new_rows:
        by_day.setdefault(r["day"], {"day": r["day"], "active": 0, "new": 0})["new"] = r["new"]

    return {
        "generated_at": now_utc().strftime("%Y-%m-%d %H:%M:%S UTC"),
        "totals": {
            "installs": total,
            "active_24h": active_1,
            "active_7d": active_7,
            "active_30d": active_30,
            "escalations": total_esc,
        },
        "versions": [{"version": r["v"], "devices": r["c"]} for r in versions],
        "models": [{"model": r["m"], "devices": r["c"]} for r in models],
        "sdks": [{"sdk": r["s"], "devices": r["c"]} for r in sdks],
        "events": {r["event"]: r["c"] for r in events},
        "daily": sorted(by_day.values(), key=lambda x: x["day"]),
    }


DASHBOARD_HTML = """<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>StaticLog 使用统计</title>
<style>
  :root{color-scheme:light dark}
  body{font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans SC",sans-serif;
       margin:0;padding:28px;max-width:1000px;margin:auto;background:#fafafa;color:#111}
  @media (prefers-color-scheme:dark){body{background:#111;color:#eee}}
  h1{font-size:21px;margin:0 0 4px}
  .sub{opacity:.6;font-size:13px;margin-bottom:22px}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:26px}
  .card{border:1px solid rgba(128,128,128,.28);border-radius:12px;padding:14px 16px}
  .num{font-size:27px;font-weight:700;letter-spacing:-.5px}
  .label{font-size:12px;opacity:.62;margin-top:2px}
  h2{font-size:15px;margin:26px 0 10px;font-weight:600}
  table{border-collapse:collapse;width:100%;font-size:14px}
  td,th{text-align:left;padding:7px 10px;border-bottom:1px solid rgba(128,128,128,.2)}
  th{font-size:12px;opacity:.6;font-weight:500}
  .bars{display:flex;align-items:flex-end;gap:3px;height:110px;margin-top:8px}
  .bar{flex:1;background:#4c8bf5;border-radius:3px 3px 0 0;min-height:2px;position:relative}
  .bar span{position:absolute;top:-17px;left:0;font-size:10px;opacity:.55}
  .bar.new{background:#f5a623}
  .legend{font-size:12px;opacity:.6;margin-top:8px}
  .err{color:#c00;padding:14px;border:1px solid #c00;border-radius:10px}
</style></head>
<body>
<h1>StaticLog 使用统计</h1>
<div class="sub" id="sub">加载中…</div>
<div id="err"></div>
<div class="grid" id="totals"></div>
<h2>近 30 天活跃 / 新增</h2>
<div class="bars" id="bars"></div>
<div class="legend">蓝＝当日活跃设备　橙＝当日新增设备</div>
<h2>版本分布</h2><table id="versions"></table>
<h2>机型分布</h2><table id="models"></table>
<h2>Android 版本</h2><table id="sdks"></table>
<script>
const token = new URLSearchParams(location.search).get('token') || '';
async function load(){
  const r = await fetch('/v1/stats?token=' + encodeURIComponent(token));
  if(!r.ok){ document.getElementById('err').innerHTML =
    '<div class="err">读取失败：HTTP ' + r.status + '（token 不对，或服务没配 STATS_TOKEN）</div>'; return; }
  const d = await r.json();
  document.getElementById('sub').textContent = '统计时间 ' + d.generated_at;
  const t = d.totals;
  document.getElementById('totals').innerHTML = [
    ['总安装数', t.installs], ['24 小时活跃', t.active_24h],
    ['7 天活跃', t.active_7d], ['30 天活跃', t.active_30d],
    ['提权成功次数', t.escalations]
  ].map(([k,v]) => '<div class="card"><div class="num">' + v + '</div><div class="label">' + k + '</div></div>').join('');
  const max = Math.max(1, ...d.daily.map(x => Math.max(x.active, x.new)));
  document.getElementById('bars').innerHTML = d.daily.map(x =>
    '<div class="bar" style="height:' + (x.active/max*100) + '%" title="' + x.day + ' 活跃 ' + x.active + '"></div>').join('');
  const fmt = (rows, key) => '<tr><th>' + key + '</th><th>设备数</th></tr>' +
    rows.map(r => '<tr><td>' + r[key.toLowerCase()] + '</td><td>' + r.devices + '</td></tr>').join('');
  document.getElementById('versions').innerHTML = fmt(d.versions, 'Version');
  document.getElementById('models').innerHTML = fmt(d.models, 'Model');
  document.getElementById('sdks').innerHTML = fmt(d.sdks, 'Sdk');
}
load().catch(e => { document.getElementById('err').innerHTML = '<div class="err">' + e + '</div>'; });
</script>
</body></html>
"""


class Handler(BaseHTTPRequestHandler):
    server_version = "StaticLogStats/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 只留一行精简日志
        sys.stderr.write("[%s] %s %s\n" % (now_utc().strftime("%H:%M:%S"), self.address_string(), fmt % args))

    # ── 工具 ──
    def _send(self, code: int, body: bytes, ctype: str = "application/json; charset=utf-8") -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code: int, obj: dict) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def _token_ok(self, token: str) -> bool:
        return bool(ADMIN_TOKEN) and token == ADMIN_TOKEN

    def _client_ip(self) -> str:
        return (self.headers.get("X-Forwarded-For") or self.client_address[0]).split(",")[0].strip()

    # ── 路由 ──
    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        if parsed.path == "/healthz":
            self._json(200, {"ok": True, "service": "staticlog-stats"})
            return
        if parsed.path == "/v1/stats":
            token = (qs.get("token") or [""])[0] or (self.headers.get("Authorization", "").removeprefix("Bearer ").strip())
            if not self._token_ok(token):
                self._json(401, {"error": "token 无效"})
                return
            days = int((qs.get("days") or ["30"])[0] or 30)
            self._json(200, build_stats(days))
            return
        if parsed.path in ("/", "/dashboard"):
            token = (qs.get("token") or [""])[0]
            if not self._token_ok(token):
                self._send(403, b"403 Forbidden: need ?token=", "text/plain; charset=utf-8")
                return
            self._send(200, DASHBOARD_HTML.encode("utf-8"), "text/html; charset=utf-8")
            return
        self._json(404, {"error": "not found"})

    def do_HEAD(self):
        self.do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != "/v1/event":
            self._json(404, {"error": "not found"})
            return
        if not rate_ok(self._client_ip()):
            self._json(429, {"error": "too many requests"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._json(400, {"error": "bad content-length"})
            return
        if length <= 0 or length > 4096:
            self._json(400, {"error": "body 大小不合法"})
            return
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._json(400, {"error": "body 不是合法 JSON"})
            return
        if not isinstance(payload, dict):
            self._json(400, {"error": "body 必须是对象"})
            return
        try:
            code, obj = record_event(payload)
        except Exception as exc:  # 数据库异常不能让服务挂掉
            sys.stderr.write("record_event failed: %r\n" % (exc,))
            self._json(500, {"error": "internal"})
            return
        self._json(code, obj)


def main() -> None:
    if not ADMIN_TOKEN:
        sys.stderr.write(
            "警告：未设置 STATS_TOKEN，/v1/stats 与 /dashboard 将全部返回 401/403。\n"
            "      启动方式：STATS_TOKEN=<长随机串> python3 staticlog_stats.py\n"
        )
    init_db()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    sys.stderr.write(f"StaticLog stats listening on :{PORT}  db={DB_PATH}\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
