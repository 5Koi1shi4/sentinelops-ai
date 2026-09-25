FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps ./apps
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl apps/demo-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24
RUN apk upgrade --no-cache libexpat
WORKDIR /app
COPY --from=build /workspace/apps/demo-service/target/demo-service-*.jar /app/app.jar
RUN mkdir -p /var/log/sentinelops && chown 10001:10001 /var/log/sentinelops
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
