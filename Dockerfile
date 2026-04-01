# STAGE 1: Kompilieren (Der "Bäcker")
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Kopiere die Projektdatei und den Quellcode
COPY pom.xml .
COPY src ./src
# Bauen der JAR-Datei (überspringt Tests für Geschwindigkeit)
RUN mvn clean package -DskipTests

# STAGE 2: Ausführen (Der "Server")
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
# Kopiere NUR die fertige JAR aus Stage 1
COPY --from=build /app/target/NeoDash-1.0.jar app.jar

# Ports und Start
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
