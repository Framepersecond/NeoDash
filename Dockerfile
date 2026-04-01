# STAGE 1: Bauen
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# STAGE 2: Ausführen
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app

# Wir kopieren die fertige Datei aus Stage 1
COPY --from=build /build/target/NeoDash-1.0.jar app.jar

# WICHTIG: Wir setzen die Umgebungsvariable für den internen Pfad
ENV NEODASH_DATA_PATH=/app/data
ENV NEODASH_SERVER_PATH=/app/servers

# Erstelle die Ordner im Container
RUN mkdir -p /app/data /app/servers

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
