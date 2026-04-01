FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app

# Installiere curl
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Fragt die GitHub-API nach dem neuesten Release und lädt die .jar herunter
RUN DOWNLOAD_URL=$(curl -fsSL https://api.github.com/repos/Framepersecond/NeoDash/releases/latest \
        | grep -o '"browser_download_url":"[^"]*\.jar"' \
        | cut -d'"' -f4) \
    && [ -n "$DOWNLOAD_URL" ] || { echo "Fehler: Kein JAR-Artefakt im neuesten Release gefunden."; exit 1; } \
    && echo "Downloading NeoDash from: $DOWNLOAD_URL" \
    && curl -fSL "$DOWNLOAD_URL" -o app.jar

# Erstelle die Standard-Ordner im Container
RUN mkdir -p /app/data /app/servers

# Setze die Umgebungsvariablen
ENV NEODASH_DATA_PATH=/app/data
ENV NEODASH_SERVER_PATH=/app/servers

# Startbefehl – PANEL_PORT wird als JVM-Property übergeben, damit der Web-Server den konfigurierten Port nutzt
ENTRYPOINT ["sh", "-c", "exec java -Dneodash.port=${PANEL_PORT:-8080} -jar app.jar"]
