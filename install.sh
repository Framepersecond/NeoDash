#!/bin/bash
# ==============================================================================
# NeoDash - Universal Setup & Installer Script
# ==============================================================================

set -e # Exit immediately on critical errors

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# --- ASCII Logo ---
echo -e "${CYAN}"
echo "    _   __         ____             __   "
echo "   / | / /__  ____/ __ \____ ______/ /_  "
echo "  /  |/ / _ \/ __/ / / / __ \/ ___/ __ \ "
echo " / /|  /  __/ /_/ /_/ / /_/ (__  ) / / / "
echo "/_/ |_/\___/\__/\____/\__,_/____/_/ /_/  "
echo -e "${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo -e "${GREEN} Welcome to the NeoDash Setup Assistant!${NC}"
echo -e "${BLUE}=====================================================${NC}\n"

# --- Package Manager Detection ---
detect_pkg_manager() {
    if command -v apt-get &>/dev/null; then
        PKG_UPDATE="apt-get update -y"
        PKG_INSTALL="apt-get install -y"
        PKG_MANAGER="apt-get"
    elif command -v dnf &>/dev/null; then
        PKG_UPDATE="dnf check-update -y || true"
        PKG_INSTALL="dnf install -y"
        PKG_MANAGER="dnf"
    elif command -v yum &>/dev/null; then
        PKG_UPDATE="yum check-update -y || true"
        PKG_INSTALL="yum install -y"
        PKG_MANAGER="yum"
    elif command -v pacman &>/dev/null; then
        PKG_UPDATE="pacman -Sy --noconfirm"
        PKG_INSTALL="pacman -S --noconfirm"
        PKG_MANAGER="pacman"
    elif command -v zypper &>/dev/null; then
        PKG_UPDATE="zypper refresh"
        PKG_INSTALL="zypper install -y"
        PKG_MANAGER="zypper"
    elif command -v apk &>/dev/null; then
        PKG_UPDATE="apk update"
        PKG_INSTALL="apk add --no-cache"
        PKG_MANAGER="apk"
    else
        echo -e "${RED}Error: No supported package manager found. Please install git and docker manually.${NC}"
        exit 1
    fi
    echo -e "${GREEN}✔ Detected package manager: $PKG_MANAGER${NC}"
}

# --- 1. System & OS Check ---
echo -e "${YELLOW}[1/5] Checking system requirements...${NC}"
OS="$(uname -s)"
if [[ "$OS" != "Linux" ]]; then
    echo -e "${RED}Error: NeoDash requires a Linux operating system.${NC}"
    exit 1
fi
echo -e "${GREEN}✔ Linux system detected.${NC}"

detect_pkg_manager

# Check if Git is installed
if ! command -v git &>/dev/null; then
    echo -e "${YELLOW}Git not found. Installing git...${NC}"
    sudo $PKG_UPDATE
    sudo $PKG_INSTALL git
fi
echo -e "${GREEN}✔ Git is available.${NC}"

# --- 2. Clone repository (if run via curl) ---
if [ ! -f "docker-compose.yml" ]; then
    echo -e "\n${YELLOW}[2/5] Downloading NeoDash files from GitHub...${NC}"
    if [ -d "NeoDash" ]; then
        echo -e "${YELLOW}Removing old NeoDash directory...${NC}"
        rm -rf NeoDash
    fi
    git clone https://github.com/Framepersecond/NeoDash.git
    cd NeoDash
else
    echo -e "\n${GREEN}✔ NeoDash files already present.${NC}"
fi

# --- 3. Docker Installation & Check ---
echo -e "\n${YELLOW}[3/5] Checking Docker environment...${NC}"
if ! command -v docker &>/dev/null; then
    echo -e "${YELLOW}Docker not found. Installing Docker...${NC}"
    curl -fsSL https://get.docker.com -o get-docker.sh
    sudo sh get-docker.sh
    rm get-docker.sh
    # Add current user to the docker group so sudo is not needed for docker commands
    sudo usermod -aG docker $USER
    echo -e "${GREEN}✔ Docker successfully installed.${NC}"
else
    echo -e "${GREEN}✔ Docker is already installed.${NC}"
fi

# --- 4. Interactive Setup Questions ---
echo -e "\n${YELLOW}[4/5] NeoDash Configuration${NC}"
echo -e "Please answer the following questions to set up your panel.\n"

# Port question
echo -ne "${CYAN}Which port should the web panel use? [8080]: ${NC}"
read PANEL_PORT < /dev/tty
PANEL_PORT=${PANEL_PORT:-8080}

# Server path question (resolved to absolute path)
echo -ne "${CYAN}Where are your Minecraft servers located? (e.g. /home/user/servers) [./servers]: ${NC}"
read SERVER_DIR_INPUT < /dev/tty
SERVER_DIR_INPUT=${SERVER_DIR_INPUT:-./servers}
mkdir -p "$SERVER_DIR_INPUT"
SERVER_PATH=$(readlink -f "$SERVER_DIR_INPUT")

# Data directory question
echo -ne "${CYAN}Where should NeoDash system data be stored? [./data]: ${NC}"
read DATA_DIR_INPUT < /dev/tty
DATA_DIR_INPUT=${DATA_DIR_INPUT:-./data}
mkdir -p "$DATA_DIR_INPUT"
DATA_PATH=$(readlink -f "$DATA_DIR_INPUT")

echo -e "\n${GREEN}✔ Saving configuration to .env...${NC}"
cat <<EOF > .env
PANEL_PORT=$PANEL_PORT
SERVER_PATH=$SERVER_PATH
DATA_PATH=$DATA_PATH
EOF

# --- 5. Container Build & Start ---
echo -e "\n${YELLOW}[5/5] Starting Docker Compose build...${NC}"
echo -e "Compiling Java 21 may take 1-2 minutes on the first run. Please wait...\n"

sudo docker compose up -d --build

# --- Final success message & IP detection ---
PUBLIC_IP=$(curl -s ifconfig.me || echo "YOUR_SERVER_IP")

echo -e "\n${BLUE}=====================================================${NC}"
echo -e "${GREEN} 🎉 SETUP COMPLETED SUCCESSFULLY! 🎉${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo -e "Your NeoDash panel is now online."
echo -e "🌐 URL: ${CYAN}http://$PUBLIC_IP:$PANEL_PORT${NC}"
echo -e "📁 Server path (Host): ${CYAN}$SERVER_PATH${NC}"
echo -e "📁 Server path (In Panel): ${RED}/app/servers/${NC} (IMPORTANT!)"
echo -e "\n${YELLOW}Tip:${NC} When adding a server in the panel, always use the path"
echo -e "starting with '/app/servers/...' (e.g. /app/servers/my_server)."
echo -e "${BLUE}=====================================================${NC}\n"
