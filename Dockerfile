# Application image: Maven and JDK are needed only during the build.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
# Integration tests are run separately with `docker compose run --rm tests`.
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/bookanear-api-0.1.0.jar /app/bookanear-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bookanear-api.jar"]
