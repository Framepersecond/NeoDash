# STAGE 1: Kompilieren
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# STAGE 2: Ausführen
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app

# Greift automatisch die richtige JAR-Datei, egal welche Version in der pom.xml steht
COPY --from=build /build/target/*.jar app.jar

# Erstelle die Standard-Ordner im Container
RUN mkdir -p /app/data /app/servers

# Startbefehl
ENTRYPOINT ["java", "-jar", "app.jar"]
