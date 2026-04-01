#!/bin/bash
# ==============================================================================
# NeoDash - Universal Setup & Installer Script
# ==============================================================================

set -e # Bricht das Script bei kritischen Fehlern sofort ab

# --- Farben definieren ---
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
echo -e "${GREEN} Willkommen beim NeoDash Setup-Assistenten!${NC}"
echo -e "${BLUE}=====================================================${NC}\n"

# --- 1. System & OS Check ---
echo -e "${YELLOW}[1/5] Überprüfe Systemvoraussetzungen...${NC}"
OS="$(uname -s)"
if [[ "$OS" != "Linux" ]]; then
    echo -e "${RED}Fehler: NeoDash erfordert ein Linux-Betriebssystem (Ubuntu/Debian empfohlen).${NC}"
    exit 1
fi
echo -e "${GREEN}✔ Linux-System erkannt.${NC}"

# Prüfe, ob Git installiert ist
if ! [ -x "$(command -v git)" ]; then
    echo -e "${YELLOW}Git nicht gefunden. Installiere Git...${NC}"
    sudo apt-get update && sudo apt-get install -y git
fi

# --- 2. Repository klonen (falls via curl ausgeführt) ---
if [ ! -f "docker-compose.yml" ]; then
    echo -e "\n${YELLOW}[2/5] Lade NeoDash-Dateien von GitHub herunter...${NC}"
    if [ -d "NeoDash" ]; then
        echo -e "${YELLOW}Lösche altes NeoDash Verzeichnis...${NC}"
        rm -rf NeoDash
    fi
    git clone https://github.com/Framepersecond/NeoDash.git
    cd NeoDash
else
    echo -e "\n${GREEN}✔ NeoDash-Dateien bereits vorhanden.${NC}"
fi

# --- 3. Docker Installation & Check ---
echo -e "\n${YELLOW}[3/5] Überprüfe Docker-Umgebung...${NC}"
if ! [ -x "$(command -v docker)" ]; then
    echo -e "${YELLOW}Docker nicht gefunden. Installiere Docker...${NC}"
    curl -fsSL https://get.docker.com -o get-docker.sh
    sudo sh get-docker.sh
    rm get-docker.sh
    # Füge User zur Docker-Gruppe hinzu, damit kein Sudo für Docker-Befehle nötig ist
    sudo usermod -aG docker $USER
    echo -e "${GREEN}✔ Docker erfolgreich installiert.${NC}"
else
    echo -e "${GREEN}✔ Docker ist bereits installiert.${NC}"
fi

# --- 4. Interaktive Setup-Abfragen ---
echo -e "\n${YELLOW}[4/5] NeoDash Konfiguration${NC}"
echo -e "Bitte beantworte die folgenden Fragen, um dein Panel einzurichten.\n"

# Port Abfrage
echo -ne "${CYAN}Welcher Port soll für das Web-Panel genutzt werden? [8080]: ${NC}"
read PANEL_PORT
PANEL_PORT=${PANEL_PORT:-8080}

# Server Pfad Abfrage (Mit Auflösung zu absolutem Pfad!)
echo -ne "${CYAN}Wo liegen deine Minecraft-Server? (z.B. /home/user/servers) [./servers]: ${NC}"
read SERVER_DIR_INPUT
SERVER_DIR_INPUT=${SERVER_DIR_INPUT:-./servers}
mkdir -p "$SERVER_DIR_INPUT"
SERVER_PATH=$(readlink -f "$SERVER_DIR_INPUT") # Wandelt ./ in absoluten Pfad um!

# Datenbank Pfad Abfrage
echo -ne "${CYAN}Wo sollen die NeoDash-Systemdaten gespeichert werden? [./data]: ${NC}"
read DATA_DIR_INPUT
DATA_DIR_INPUT=${DATA_DIR_INPUT:-./data}
mkdir -p "$DATA_DIR_INPUT"
DATA_PATH=$(readlink -f "$DATA_DIR_INPUT")

echo -e "\n${GREEN}✔ Konfiguration wird gespeichert in .env...${NC}"
cat <<EOF > .env
PANEL_PORT=$PANEL_PORT
SERVER_PATH=$SERVER_PATH
DATA_PATH=$DATA_PATH
EOF
# --- 5. Container Build & Start ---
echo -e "\n${YELLOW}[5/5] Starte Docker Compose Build...${NC}"
echo -e "Das Kompilieren von Java 21 kann beim ersten Mal 1-2 Minuten dauern. Bitte warten...\n"

sudo docker compose up -d --build

# --- Finale Erfolgsmeldung & IP Erkennung ---
PUBLIC_IP=$(curl -s ifconfig.me || echo "DEINE_SERVER_IP")

echo -e "\n${BLUE}=====================================================${NC}"
echo -e "${GREEN} 🎉 SETUP ERFOLGREICH ABGESCHLOSSEN! 🎉${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo -e "Dein NeoDash Panel ist jetzt online."
echo -e "🌐 URL: ${CYAN}http://$PUBLIC_IP:$PANEL_PORT${NC}"
echo -e "📁 Server-Pfad (Host): ${CYAN}$SERVER_PATH${NC}"
echo -e "📁 Server-Pfad (Im Panel): ${RED}/app/servers/${NC} (WICHTIG!)"
echo -e "\n${YELLOW}Tipp:${NC} Nutze beim Anlegen eines Servers im Panel immer den Pfad"
echo -e "beginnend mit '/app/servers/...' (z.B. /app/servers/mein_server)."
echo -e "${BLUE}=====================================================${NC}\n"
