FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/ticketflow-1.0.0.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
