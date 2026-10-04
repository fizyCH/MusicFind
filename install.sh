#!/usr/bin/env bash
#
# MusicFind server installer (Linux + systemd).
#
# Installs Python dependencies into server/.venv, creates the runtime data
# directory and a .env file (with a random admin password), registers a
# systemd service and starts it.
#
# Usage:
#   sudo ./install.sh            # install / update
#   sudo ./install.sh --port 8081
#   sudo ./install.sh --uninstall
#
set -euo pipefail

SERVICE_NAME="musicfind-api"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$REPO_DIR/server"
VENV_DIR="$SERVER_DIR/.venv"
DATA_DIR="$SERVER_DIR/data"
ENV_FILE="$SERVER_DIR/.env"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
PORT="8081"

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

# --- args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="${2:?}"; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -n 20
      exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

[[ "$(id -u)" -eq 0 ]] || die "Run as root: sudo ./install.sh"

# --- uninstall ---
if [[ "${UNINSTALL:-0}" == "1" ]]; then
  log "Stopping and removing ${SERVICE_NAME}"
  systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
  rm -f "$UNIT_FILE"
  systemctl daemon-reload || true
  log "Service removed. Data kept in $DATA_DIR (delete manually if needed)."
  exit 0
fi

# --- system packages ---
if command -v apt-get >/dev/null 2>&1; then
  log "Installing system packages (python3, venv, ffmpeg)"
  apt-get update -y
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    python3 python3-venv python3-pip ffmpeg curl ca-certificates
else
  warn "apt-get not found — make sure python3, python3-venv and ffmpeg are installed."
fi

command -v python3 >/dev/null 2>&1 || die "python3 is required"

# --- python venv ---
log "Creating virtualenv at $VENV_DIR"
python3 -m venv "$VENV_DIR"
"$VENV_DIR/bin/pip" install --upgrade pip wheel >/dev/null
log "Installing Python dependencies"
"$VENV_DIR/bin/pip" install -r "$SERVER_DIR/requirements.txt"

# --- data dir & .env ---
mkdir -p "$DATA_DIR/app"
if [[ ! -f "$ENV_FILE" ]]; then
  log "Creating .env with a random admin password"
  ADMIN_PASSWORD="$(python3 -c 'import secrets;print(secrets.token_urlsafe(12))')"
  cat > "$ENV_FILE" <<EOF
HOST=0.0.0.0
PORT=$PORT
ADMIN_PASSWORD=$ADMIN_PASSWORD
APP_APK_PATH=$DATA_DIR/app/MusicFind.apk
AUTO_UPDATE=1
AUTO_UPDATE_INTERVAL_SECONDS=86400
EOF
  GENERATED_PASSWORD="$ADMIN_PASSWORD"
else
  log "Keeping existing $ENV_FILE"
fi

# --- systemd service ---
log "Writing systemd unit $UNIT_FILE"
cat > "$UNIT_FILE" <<EOF
[Unit]
Description=MusicFind API & Admin Panel
After=network.target

[Service]
User=root
WorkingDirectory=$SERVER_DIR
ExecStart=$VENV_DIR/bin/python $SERVER_DIR/main.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
systemctl restart "$SERVICE_NAME"

sleep 1
if systemctl is-active --quiet "$SERVICE_NAME"; then
  log "MusicFind server is running."
else
  die "Service failed to start. Check: journalctl -u $SERVICE_NAME -e"
fi

IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo
echo "-------------------------------------------------------------"
echo " MusicFind server installed"
echo "  API:    http://${IP:-<server-ip>}:$PORT"
echo "  Admin:  http://${IP:-<server-ip>}:$PORT/admin"
echo "  Config: $ENV_FILE"
if [[ -n "${GENERATED_PASSWORD:-}" ]]; then
  echo "  Admin password: $GENERATED_PASSWORD"
  echo "  (save it — it is only shown once)"
fi
echo "-------------------------------------------------------------"
