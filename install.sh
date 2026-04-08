#!/usr/bin/env bash
# ==============================================================================
# NeoDash - Native Linux Installer (systemd / screen)
# ==============================================================================
set -euo pipefail

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}"
echo "    _   __         ____             __   "
echo "   / | / /__  ____/ __ \____ ______/ /_  "
echo "  /  |/ / _ \/ __/ / / / __ \/ ___/ __ \ "
echo " / /|  /  __/ /_/ /_/ / /_/ (__  ) / / / "
echo "/_/ |_/\___/\__/\____/\__,_/____/_/ /_/  "
echo -e "${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo -e "${GREEN} Welcome to the NeoDash Native Installer!${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo

# --- Determine the real user (even when run via sudo) ---
INSTALL_USER="${SUDO_USER:-$(id -un)}"
INSTALL_HOME="$(eval echo ~"$INSTALL_USER")"

# --- 1. OS check ---
echo -e "${YELLOW}[1/6] Checking system...${NC}"
if [[ "$(uname -s)" != "Linux" ]]; then
    echo -e "${RED}Error: NeoDash requires Linux.${NC}"
    exit 1
fi
echo -e "${GREEN}✔ Linux detected (running as: $INSTALL_USER)${NC}"

# --- 2. Package manager detection ---
detect_pkg_manager() {
    if   command -v apt-get &>/dev/null; then PKG_UPDATE="apt-get update -y";     PKG_INSTALL="apt-get install -y"; PKG_MANAGER="apt-get"
    elif command -v pacman  &>/dev/null; then PKG_UPDATE="pacman -Sy --noconfirm"; PKG_INSTALL="pacman -S --noconfirm"; PKG_MANAGER="pacman"
    elif command -v dnf     &>/dev/null; then PKG_UPDATE="dnf check-update -y||true"; PKG_INSTALL="dnf install -y"; PKG_MANAGER="dnf"
    elif command -v yum     &>/dev/null; then PKG_UPDATE="yum check-update -y||true"; PKG_INSTALL="yum install -y"; PKG_MANAGER="yum"
    elif command -v zypper  &>/dev/null; then PKG_UPDATE="zypper refresh";         PKG_INSTALL="zypper install -y"; PKG_MANAGER="zypper"
    elif command -v apk     &>/dev/null; then PKG_UPDATE="apk update";             PKG_INSTALL="apk add --no-cache"; PKG_MANAGER="apk"
    else
        echo -e "${RED}Error: No supported package manager found.${NC}"
        exit 1
    fi
    echo -e "${GREEN}✔ Package manager: $PKG_MANAGER${NC}"
}
detect_pkg_manager

# --- 3. Java 21+ ---
echo -e "\n${YELLOW}[2/6] Checking Java 21+...${NC}"
JAVA_BIN=""

detect_java() {
    local candidate="$1"
    if [[ ! -x "$candidate" ]]; then return 1; fi
    local ver
    ver=$("$candidate" -version 2>&1 | grep -oE '"[0-9]+' | tr -d '"' | head -1)
    if [[ "${ver:-0}" -ge 21 ]]; then
        JAVA_BIN="$candidate"
        echo -e "${GREEN}✔ Java $ver found at $candidate${NC}"
        return 0
    fi
    return 1
}

if ! detect_java "$(command -v java 2>/dev/null || echo '')"; then
    echo -e "${YELLOW}Java 21+ not found. Installing...${NC}"
    sudo $PKG_UPDATE 2>/dev/null || true
    case "$PKG_MANAGER" in
        apt-get) sudo $PKG_INSTALL openjdk-21-jdk || sudo $PKG_INSTALL openjdk-21-jdk-headless ;;
        pacman)  sudo $PKG_INSTALL jdk21-openjdk ;;
        dnf|yum) sudo $PKG_INSTALL java-21-openjdk ;;
        zypper)  sudo $PKG_INSTALL java-21-openjdk ;;
        apk)     sudo $PKG_INSTALL openjdk21 ;;
    esac
    detect_java "$(command -v java)" || { echo -e "${RED}Java 21 installation failed. Install manually and re-run.${NC}"; exit 1; }
fi

# --- 4. screen ---
echo -e "\n${YELLOW}[3/6] Checking screen (for Minecraft server management)...${NC}"
if ! command -v screen &>/dev/null; then
    echo -e "${YELLOW}Installing screen...${NC}"
    sudo $PKG_INSTALL screen
fi
echo -e "${GREEN}✔ screen available${NC}"

# --- 5. Download NeoDash JAR ---
echo -e "\n${YELLOW}[4/6] Downloading latest NeoDash JAR...${NC}"
NEODASH_DIR="$INSTALL_HOME/NeoDash"
mkdir -p "$NEODASH_DIR"
JAR_PATH="$NEODASH_DIR/neodash.jar"

JAR_URL=$(curl -fsSL https://api.github.com/repos/Framepersecond/NeoDash/releases/latest \
    | grep '"browser_download_url"' | grep '\.jar"' | head -1 | cut -d'"' -f4)

if [[ -z "$JAR_URL" ]]; then
    echo -e "${RED}Error: Could not find a JAR asset in the latest release.${NC}"
    echo -e "${YELLOW}Check: https://github.com/Framepersecond/NeoDash/releases${NC}"
    exit 1
fi

echo -e "Fetching: $JAR_URL"
curl -fSL "$JAR_URL" -o "$JAR_PATH"
echo -e "${GREEN}✔ NeoDash JAR downloaded to $JAR_PATH${NC}"

# --- 6. Configuration ---
echo -e "\n${YELLOW}[5/6] Configuration${NC}"

expand_path() {
    local p="${1/#\~/$INSTALL_HOME}"
    echo "$p"
}

echo -ne "${CYAN}Web panel port? [8080]: ${NC}"
read -r PANEL_PORT </dev/tty
PANEL_PORT="${PANEL_PORT:-8080}"

echo -ne "${CYAN}Where should Minecraft servers be stored? [$INSTALL_HOME/servers]: ${NC}"
read -r SERVER_DIR_INPUT </dev/tty
SERVER_DIR_INPUT="$(expand_path "${SERVER_DIR_INPUT:-$INSTALL_HOME/servers}")"
mkdir -p "$SERVER_DIR_INPUT"
SERVER_PATH="$(readlink -f "$SERVER_DIR_INPUT")"

echo -ne "${CYAN}Where should NeoDash store its data? [$NEODASH_DIR/data]: ${NC}"
read -r DATA_DIR_INPUT </dev/tty
DATA_DIR_INPUT="$(expand_path "${DATA_DIR_INPUT:-$NEODASH_DIR/data}")"
mkdir -p "$DATA_DIR_INPUT"
DATA_PATH="$(readlink -f "$DATA_DIR_INPUT")"

# Ensure directories are owned by the install user (if run via sudo)
if [[ -n "${SUDO_USER:-}" ]]; then
    chown -R "$INSTALL_USER:$(id -gn "$INSTALL_USER")" "$NEODASH_DIR" "$DATA_PATH" "$SERVER_PATH" 2>/dev/null || true
fi

# Save human-readable config
cat > "$NEODASH_DIR/.env" <<EOF
PANEL_PORT=$PANEL_PORT
SERVER_PATH=$SERVER_PATH
DATA_PATH=$DATA_PATH
JAR_PATH=$JAR_PATH
JAVA_BIN=$JAVA_BIN
EOF
echo -e "${GREEN}✔ Config saved to $NEODASH_DIR/.env${NC}"

# --- 7. Service setup ---
echo -e "\n${YELLOW}[6/6] Setting up NeoDash service...${NC}"

EXEC_CMD="$JAVA_BIN -Dneodash.port=$PANEL_PORT -Dneodash.serverDir=$SERVER_PATH -jar $JAR_PATH"

if command -v systemctl &>/dev/null && systemctl --version &>/dev/null 2>&1; then
    # ── systemd ──
    SERVICE_FILE="/etc/systemd/system/neodash.service"
    sudo tee "$SERVICE_FILE" > /dev/null <<UNIT
[Unit]
Description=NeoDash Minecraft Admin Panel
After=network.target

[Service]
Type=simple
User=$INSTALL_USER
WorkingDirectory=$DATA_PATH
ExecStart=$EXEC_CMD
Restart=on-failure
RestartSec=5
SyslogIdentifier=neodash

[Install]
WantedBy=multi-user.target
UNIT

    sudo systemctl daemon-reload
    sudo systemctl enable neodash
    sudo systemctl restart neodash

    echo -e "${GREEN}✔ NeoDash running as systemd service${NC}"
    echo -e "   Status : ${CYAN}sudo systemctl status neodash${NC}"
    echo -e "   Logs   : ${CYAN}journalctl -u neodash -f${NC}"
    echo -e "   Stop   : ${CYAN}sudo systemctl stop neodash${NC}"
    echo -e "   Restart: ${CYAN}sudo systemctl restart neodash${NC}"
else
    # ── screen fallback ──
    START_SCRIPT="$NEODASH_DIR/start.sh"
    STOP_SCRIPT="$NEODASH_DIR/stop.sh"

    cat > "$START_SCRIPT" <<SCRIPT
#!/usr/bin/env bash
source "\$(dirname "\$0")/.env"
if screen -list | grep -q "neodash"; then
    echo "NeoDash is already running. Use stop.sh first."
    exit 1
fi
screen -dmS neodash \$JAVA_BIN -Dneodash.port=\$PANEL_PORT -Dneodash.serverDir=\$SERVER_PATH -jar \$JAR_PATH
echo "NeoDash started. Attach: screen -r neodash"
SCRIPT

    cat > "$STOP_SCRIPT" <<SCRIPT
#!/usr/bin/env bash
screen -S neodash -X quit && echo "NeoDash stopped." || echo "NeoDash not running."
SCRIPT

    chmod +x "$START_SCRIPT" "$STOP_SCRIPT"

    # Add start.sh to user crontab for boot persistence
    (crontab -l -u "$INSTALL_USER" 2>/dev/null || true; echo "@reboot $START_SCRIPT") \
        | sort -u | crontab -u "$INSTALL_USER" -

    # Start now
    sudo -u "$INSTALL_USER" "$START_SCRIPT"

    echo -e "${GREEN}✔ NeoDash started in screen session (systemd not available)${NC}"
    echo -e "   Attach : ${CYAN}screen -r neodash${NC}"
    echo -e "   Stop   : ${CYAN}$STOP_SCRIPT${NC}"
    echo -e "   Restart: ${CYAN}$STOP_SCRIPT && $START_SCRIPT${NC}"
    echo -e "   Auto-start on boot added to crontab."
fi

# --- Done ---
PUBLIC_IP=$(curl -s --max-time 5 ifconfig.me 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo "YOUR_SERVER_IP")

echo
echo -e "${BLUE}=====================================================${NC}"
echo -e "${GREEN} 🎉 NeoDash is running!${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo -e "🌐 Panel URL : ${CYAN}http://$PUBLIC_IP:$PANEL_PORT${NC}"
echo -e "📁 Servers   : ${CYAN}$SERVER_PATH${NC}"
echo -e "📁 Data      : ${CYAN}$DATA_PATH${NC}"
echo -e "☕ Java      : ${CYAN}$JAVA_BIN${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo -e "${YELLOW}Tip:${NC} When installing servers from the panel, use a path"
echo -e "     inside ${CYAN}$SERVER_PATH${NC} (e.g. ${CYAN}$SERVER_PATH/my-server${NC})."
echo
