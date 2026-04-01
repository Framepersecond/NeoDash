#!/bin/bash
# NeoDash Interactive Setup

# Farben
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}=== NeoDash Setup Experience ===${NC}"

# 1. Abfragen
read -p "Welcher Port soll für das Web-Panel genutzt werden? [Standard: 8080]: " PANEL_PORT
PANEL_PORT=${PANEL_PORT:-8080}

read -p "Wo sollen die Datenbank-Daten gespeichert werden? [Standard: ./data]: " DATA_DIR
DATA_DIR=${DATA_DIR:-./data}

read -p "Wo liegen deine Minecraft-Server? [Standard: ./servers]: " SERVER_DIR
SERVER_DIR=${SERVER_DIR:-./servers}

# 2. .env Datei erstellen
echo -e "${YELLOW}Erstelle Konfiguration...${NC}"
cat <<EOF > .env
PANEL_PORT=$PANEL_PORT
DATA_PATH=$DATA_DIR
SERVER_PATH=$SERVER_DIR
EOF

# 3. Docker Check & Start
if ! [ -x "$(command -v docker)" ]; then
    echo -e "${YELLOW}Docker wird installiert...${NC}"
    curl -fsSL https://get.docker.com -o get-docker.sh && sudo sh get-docker.sh
fi

echo -e "${GREEN}Starte NeoDash Container...${NC}"
sudo docker compose up -d --build

echo -e "${BLUE}=======================================${NC}"
echo -e "${GREEN}SETUP ERFOLGREICH!${NC}"
echo -e "Panel-URL: ${BLUE}http://localhost:$PANEL_PORT${NC}"
echo -e "${BLUE}=======================================${NC}"#!/bin/bash

# --- Farben für die Optik ---
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}=======================================${NC}"
echo -e "${GREEN}    NeoDash Universal Installer        ${NC}"
echo -e "${BLUE}=======================================${NC}"

# 1. OS Detection
OS="$(uname -s)"
echo -e "${YELLOW}[1/3] Detecting OS...${NC} Found: $OS"

# 2. Dependency Check (Docker)
if ! [ -x "$(command -v docker)" ]; then
  echo -e "${YELLOW}[2/3] Docker not found. Installing Docker...${NC}"
  if [[ "$OS" == "Linux" ]]; then
    curl -fsSL https://get.docker.com -o get-docker.sh
    sudo sh get-docker.sh
    sudo usermod -aG docker $USER
    rm get-docker.sh
  else
    echo -e "${RED}Please install Docker Desktop for $OS manually and restart this script.${NC}"
    exit 1
  fi
else
  echo -e "${GREEN}[2/3] Docker is already installed.${NC}"
fi

# 3. Start Application
echo -e "${YELLOW}[3/3] Starting NeoDash in Docker...${NC}"
sudo docker compose up -d --build

echo -e "${BLUE}=======================================${NC}"
echo -e "${GREEN}INSTALLATION COMPLETE!${NC}"
echo -e "Du erreichst dein Panel jetzt unter: ${BLUE}http://localhost:8080${NC}"
echo -e "Die Server-Dateien liegen im Ordner: ${BLUE}./servers${NC}"
echo -e "${BLUE}=======================================${NC}"
