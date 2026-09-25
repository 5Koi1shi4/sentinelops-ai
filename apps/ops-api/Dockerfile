FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps ./apps
COPY evals/datasets ./evals/datasets
COPY deploy/maven-container-settings.xml ./maven-settings.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /workspace/maven-settings.xml -pl apps/ops-api -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24
RUN apk upgrade --no-cache libexpat
WORKDIR /app
COPY --from=build /workspace/apps/ops-api/target/ops-api-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
