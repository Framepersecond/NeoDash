# STAGE 1: Kompilieren
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# STAGE 2: Ausführen
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app

# WICHTIG: Wir nehmen exakt die Fat JAR und ignorieren die "original-*.jar"
COPY --from=build /build/target/NeoDash-1.0.jar app.jar

ENV NEODASH_DATA_PATH=/app/data
ENV NEODASH_SERVER_PATH=/app/servers

RUN mkdir -p /app/data /app/servers

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
