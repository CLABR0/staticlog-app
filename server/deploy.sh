#!/usr/bin/env bash
# StaticLog 统计服务一键部署（Ubuntu 22.04/24.04，在 ECS 上以 root 执行）
set -euo pipefail

PORT="${PORT:-8080}"
APP_DIR=/opt/staticlog
SRC="$(cd "$(dirname "$0")/.." && pwd)/staticlog_stats.py"

[ -f "$SRC" ] || { echo "找不到 staticlog_stats.py（脚本要在 server/deploy 下，或自行改 SRC）"; exit 1; }
command -v python3 >/dev/null || { echo "缺 python3"; exit 1; }

TOKEN="${STATS_TOKEN:-$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')}"

id staticlog >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin staticlog
mkdir -p "$APP_DIR"
install -m 0644 "$SRC" "$APP_DIR/staticlog_stats.py"
chown -R staticlog:staticlog "$APP_DIR"

cat >/etc/systemd/system/staticlog-stats.service <<UNIT
[Unit]
Description=StaticLog usage stats
After=network.target

[Service]
Type=simple
User=staticlog
WorkingDirectory=$APP_DIR
Environment=PORT=$PORT
Environment=DB=$APP_DIR/stats.db
Environment=STATS_TOKEN=$TOKEN
ExecStart=/usr/bin/python3 $APP_DIR/staticlog_stats.py
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
sleep 2
systemctl is-active staticlog-stats

echo
echo "================ 部署完成 ================"
echo "端口   : $PORT"
echo "Token  : $TOKEN"
echo "看板   : http://<公网IP>:$PORT/dashboard?token=$TOKEN"
echo
echo "记得在阿里云安全组放行 TCP $PORT"
echo "客户端接入：ztui/gradle.properties 加一行"
echo "  staticlog.statsEndpoint=http://<公网IP>:$PORT"
echo "=========================================="