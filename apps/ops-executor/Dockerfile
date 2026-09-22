FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps ./apps
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl apps/ops-executor -am -Dmaven.test.skip=true package

FROM maven:3.9.11-eclipse-temurin-21
WORKDIR /app
COPY --from=build /workspace/apps/ops-executor/target/ops-executor-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
