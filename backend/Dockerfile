FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build --chown=appuser:appuser /build/target/intellitutor-backend-*.jar /app/app.jar

USER appuser
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=10 \
  CMD ["bash", "-ec", ": </dev/tcp/127.0.0.1/8090"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

