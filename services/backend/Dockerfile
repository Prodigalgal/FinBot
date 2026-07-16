# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM eclipse-temurin:26-jdk-noble AS builder
WORKDIR /workspace
COPY services/backend/ ./
RUN chmod +x gradlew \
    && ./gradlew --no-daemon :finbot-bootstrap:bootJar :finbot-migration:bootJar

FROM eclipse-temurin:26-jre-noble
RUN groupadd --gid 10001 finbot \
    && useradd --uid 10001 --gid finbot --shell /usr/sbin/nologin --no-create-home finbot
WORKDIR /app
COPY --from=builder /workspace/finbot-bootstrap/build/libs/finbot-bootstrap-2.0.0-SNAPSHOT.jar /app/finbot.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/finbot.jar"]
