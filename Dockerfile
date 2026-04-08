FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

# Install curl, jq, screen, and procps
RUN apk add --no-cache curl jq screen procps

# Fetch the latest release from the GitHub API and download the JAR
RUN DOWNLOAD_URL=$(curl -fsSL https://api.github.com/repos/Framepersecond/NeoDash/releases/latest \
        | jq -r '.assets[] | select(.name | endswith(".jar")) | .browser_download_url') \
    && [ -n "$DOWNLOAD_URL" ] || { echo "Error: No JAR artifact found in the latest release."; exit 1; } \
    && echo "Downloading NeoDash from: $DOWNLOAD_URL" \
    && curl -fSL "$DOWNLOAD_URL" -o app.jar

# Create default directories inside the container
RUN mkdir -p /app/data /app/servers

# Set environment variables
ENV NEODASH_DATA_PATH=/app/data
ENV NEODASH_SERVER_PATH=/app/servers

# Start command – PANEL_PORT is passed as a JVM property so the web server uses the configured port
ENTRYPOINT ["sh", "-c", "exec java -Dneodash.port=${PANEL_PORT:-8080} -jar app.jar"]
