# Wir nutzen Java 21 (wie in deinem Projekt verwendet)
FROM eclipse-temurin:21-jdk-jammy

# Arbeitsverzeichnis im Container
WORKDIR /app

# Kopiere das fertige JAR in den Container
# (Wir gehen davon aus, dass es vorher mit mvn package gebaut wurde)
COPY target/NeoDash-1.0.jar app.jar

# Ports freigeben (Admin Web Server & Minecraft Standard Port)
EXPOSE 8080
EXPOSE 25565

# Startbefehl
ENTRYPOINT ["java", "-jar", "app.jar"]
