# vulnscan in a container: Java 21 + the CLI + pinned jadx. Only API keys and the APK are provided at run time.
#
#   docker build -t vulnscan .
#   docker run --rm --env-file .env \
#     -v "$PWD/apps:/app/apps" -v "$PWD/runs:/app/runs" -v "$PWD/cache:/app/cache" \
#     vulnscan run --config configs/insecureshop.yaml
#
# apps/ holds the APK (downloaded as described in the README), runs/ receives the run folders, cache/ keeps the
# decompile and LLM response caches between runs. Keys come from --env-file (never baked into the image).

# ---- build stage: compile and package with the Maven wrapper (Maven and dependencies stay inside this stage) ----
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS build
WORKDIR /build
# The Maven wrapper downloads Maven itself (checksum-pinned in .mvn/wrapper) and needs curl + unzip.
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/*
# Dependencies first, so source changes do not re-download them.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && MAVEN_USER_HOME=/build/.tools/maven ./mvnw -q -B dependency:go-offline
COPY src src
COPY prompts prompts
COPY configs configs
COPY ground_truth ground_truth
# Tests run here too (no network needed: they use fake models).
RUN MAVEN_USER_HOME=/build/.tools/maven ./mvnw -q -B package

# ---- jadx: pinned release, checksum verified ----
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS jadx
ARG JADX_VERSION=1.5.6
ARG JADX_SHA256=545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tmp/jadx.zip "https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip" \
 && echo "${JADX_SHA256}  /tmp/jadx.zip" | sha256sum -c - \
 && mkdir -p /opt/jadx && unzip -q /tmp/jadx.zip -d /opt/jadx && chmod +x /opt/jadx/bin/jadx && rm /tmp/jadx.zip

# ---- runtime: JRE + jar + prompts/configs/ground truth + jadx, non-root ----
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515
RUN useradd --create-home --uid 10001 vulnscan
WORKDIR /app
COPY --from=build /build/target/vulnscan.jar /app/vulnscan.jar
COPY --from=build /build/prompts /app/prompts
COPY --from=build /build/configs /app/configs
COPY --from=build /build/ground_truth /app/ground_truth
# Same path as the project-local layout, so configs work unchanged (decompile.jadx_path: .tools/jadx/bin/jadx).
COPY --from=jadx /opt/jadx /app/.tools/jadx
RUN mkdir -p /app/apps /app/runs /app/cache && chown -R vulnscan:vulnscan /app
USER vulnscan
ENTRYPOINT ["java", "-jar", "/app/vulnscan.jar"]
CMD ["--help"]
