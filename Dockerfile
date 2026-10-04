# ---------------------------------------------------------------------------
# Stage 1 — build a self-contained "assembly" jar with Scala CLI
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build

ARG SCALA_CLI_VERSION=1.15.0
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://github.com/VirtusLab/scala-cli/releases/download/v${SCALA_CLI_VERSION}/scala-cli-x86_64-pc-linux.gz" \
    | gunzip > /usr/local/bin/scala-cli \
 && chmod +x /usr/local/bin/scala-cli

WORKDIR /app
COPY project.scala ./
COPY src ./src
COPY resources ./resources

RUN scala-cli --power package . \
      --server=false \
      --main-class restaurants.web.WebServer \
      --assembly --preamble=false \
      -o /app/mise.jar --force

# ---------------------------------------------------------------------------
# Stage 2 — small runtime image
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/mise.jar ./mise.jar

# Render injects PORT; 8080 is the local default.
ENV PORT=8080
EXPOSE 8080

# Sized for a 512 MB free instance.
CMD ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-Xss512k", "-jar", "/app/mise.jar"]
