FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app

# Installiere curl und wget
RUN apt-get update && apt-get install -y curl wget && rm -rf /var/lib/apt/lists/*

# Magie: Fragt die GitHub-API nach dem neuesten Release, sucht den Download-Link der .jar und lädt sie als app.jar herunter
RUN curl -s https://api.github.com/repos/Framepersecond/NeoDash/releases/latest \
    | grep "browser_download_url.*\.jar" \
    | cut -d '"' -f 4 \
    | wget -qi - -O app.jar

# Erstelle die Standard-Ordner im Container
RUN mkdir -p /app/data /app/servers

# Setze die Umgebungsvariablen
ENV NEODASH_DATA_PATH=/app/data
ENV NEODASH_SERVER_PATH=/app/servers

# Startbefehl
ENTRYPOINT ["java", "-jar", "app.jar"]
