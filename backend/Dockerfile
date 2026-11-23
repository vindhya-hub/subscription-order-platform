# syntax=docker/dockerfile:1

# ---- Build: compile, test-free package, then split the fat jar into cache-friendly layers ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Dependencies first, so this layer is reused until pom.xml changes.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline

COPY src ./src
# The extracted thin jar keeps the input file's name, so copy it to application.jar first:
# that is the name the ENTRYPOINT below starts.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package \
 && cp target/subscription-order-platform-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime: JRE 17 only, non-root ----
FROM eclipse-temurin:17-jre-noble
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# Least- to most-frequently changing, so a code change only rebuilds the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# No configuration is baked in: profiles, datasource, AWS settings etc. come from the environment at run time.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/actuator/health/readiness" || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
