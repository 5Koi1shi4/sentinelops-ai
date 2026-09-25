FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps ./apps
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl apps/ops-executor -am -Dmaven.test.skip=true package

FROM alpine:3.24
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en LC_ALL=en_US.UTF-8
COPY deploy/images/install-temurin21-jre.sh /tmp/install-temurin21-jre.sh
RUN /bin/sh /tmp/install-temurin21-jre.sh && rm /tmp/install-temurin21-jre.sh
WORKDIR /app
COPY --from=build /workspace/apps/ops-executor/target/ops-executor-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
