#!/usr/bin/env bash
#
# MusicFind server installer (Linux + systemd).
#
# Can be run in two ways:
#
#   1) From a cloned checkout (uses the local files):
#        git clone <repo> musicfind && cd musicfind && sudo ./install.sh
#
#   2) Standalone — it will fetch the scripts from the repository itself:
#        sudo ./install.sh
#        curl -fsSL <raw-install.sh-url> | sudo bash
#
# Options:
#   --repo <url>      repository to fetch from (default: the project repo)
#   --branch <name>   branch to fetch (default: main)
#   --dir <path>      where to install the server (default: /opt/musicfind)
#   --port <port>     API port (default: 8081)
#   --uninstall       stop and remove the systemd service
#
set -euo pipefail

SERVICE_NAME="musicfind-api"
DEFAULT_REPO="https://github.com/fizyCH/MusicFind.git"
DEFAULT_BRANCH="main"
DEFAULT_DIR="/opt/musicfind"

REPO_URL="$DEFAULT_REPO"
BRANCH="$DEFAULT_BRANCH"
INSTALL_DIR="$DEFAULT_DIR"
PORT="8081"
UNINSTALL=0

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

# --- args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO_URL="${2:?}"; shift 2 ;;
    --branch) BRANCH="${2:?}"; shift 2 ;;
    --dir) INSTALL_DIR="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -n 24
      exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

[[ "$(id -u)" -eq 0 ]] || die "Run as root: sudo ./install.sh"

UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# --- uninstall ---
if [[ "$UNINSTALL" == "1" ]]; then
  log "Stopping and removing ${SERVICE_NAME}"
  systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
  rm -f "$UNIT_FILE"
  systemctl daemon-reload || true
  log "Service removed. Files are kept in ${INSTALL_DIR} (delete manually if needed)."
  exit 0
fi

# --- system packages (git needed before fetching) ---
if command -v apt-get >/dev/null 2>&1; then
  log "Installing system packages (python3, venv, ffmpeg, git)"
  apt-get update -y
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    python3 python3-venv python3-pip ffmpeg git curl ca-certificates
else
  warn "apt-get not found — make sure python3, python3-venv, ffmpeg and git are installed."
fi

command -v python3 >/dev/null 2>&1 || die "python3 is required"

# --- locate or fetch the project ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$SCRIPT_DIR/server/main.py" && -f "$SCRIPT_DIR/server/requirements.txt" ]]; then
  REPO_DIR="$SCRIPT_DIR"
  log "Using the local checkout at $REPO_DIR"
else
  command -v git >/dev/null 2>&1 || die "git is required to fetch the project"
  REPO_DIR="$INSTALL_DIR"
  if [[ -d "$REPO_DIR/.git" ]]; then
    log "Updating existing checkout in $REPO_DIR"
    git -C "$REPO_DIR" fetch --depth 1 origin "$BRANCH"
    git -C "$REPO_DIR" checkout -q "$BRANCH"
    git -C "$REPO_DIR" reset --hard "origin/$BRANCH"
  else
    log "Fetching scripts from $REPO_URL ($BRANCH)"
    mkdir -p "$(dirname "$REPO_DIR")"
    git clone --branch "$BRANCH" --depth 1 "$REPO_URL" "$REPO_DIR" \
      || die "Could not clone $REPO_URL. If the repository is private, authenticate git first (or clone it manually and run install.sh from there)."
  fi
fi

SERVER_DIR="$REPO_DIR/server"
VENV_DIR="$SERVER_DIR/.venv"
DATA_DIR="$SERVER_DIR/data"
ENV_FILE="$SERVER_DIR/.env"

[[ -f "$SERVER_DIR/main.py" ]] || die "server/main.py not found in $REPO_DIR"

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
echo "  Files:  $REPO_DIR"
echo "  API:    http://${IP:-<server-ip>}:$PORT"
echo "  Admin:  http://${IP:-<server-ip>}:$PORT/admin"
echo "  Config: $ENV_FILE"
if [[ -n "${GENERATED_PASSWORD:-}" ]]; then
  echo "  Admin password: $GENERATED_PASSWORD"
  echo "  (save it — it is only shown once)"
fi
echo "-------------------------------------------------------------"
