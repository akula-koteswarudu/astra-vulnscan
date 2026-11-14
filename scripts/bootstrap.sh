#!/usr/bin/env bash
# Installs all tools into .tools/ (project-local, pinned, checksum-verified). Nothing global.
# Usage: bash scripts/bootstrap.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS/downloads"

JADX_VERSION="1.5.6"
JADX_URL="https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip"
JADX_SHA256="545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974"
# Temurin JDK 21 for macOS arm64. Other platforms: set JDK_URL/JDK_SHA256 env vars.
JDK_URL="${JDK_URL:-https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz}"
JDK_SHA256="${JDK_SHA256:-bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a}"

fetch() {  # url sha256 dest
  local url="$1" sha="$2" dest="$3"
  if [ ! -f "$dest" ]; then
    echo "downloading $(basename "$dest")"
    curl -fL --retry 3 -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  echo "$sha  $dest" | shasum -a 256 -c - >/dev/null || { echo "checksum mismatch: $dest"; rm -f "$dest"; exit 1; }
}

# 1. JDK
if [ ! -d "$TOOLS/jdk" ]; then
  fetch "$JDK_URL" "$JDK_SHA256" "$TOOLS/downloads/jdk.tar.gz"
  mkdir -p "$TOOLS/jdk.tmp" && tar -xzf "$TOOLS/downloads/jdk.tar.gz" -C "$TOOLS/jdk.tmp" && mv "$TOOLS/jdk.tmp" "$TOOLS/jdk"
fi

# 2. jadx
if [ ! -x "$TOOLS/jadx/bin/jadx" ]; then
  fetch "$JADX_URL" "$JADX_SHA256" "$TOOLS/downloads/jadx.zip"
  rm -rf "$TOOLS/jadx.tmp" && mkdir -p "$TOOLS/jadx.tmp" && unzip -q "$TOOLS/downloads/jadx.zip" -d "$TOOLS/jadx.tmp" && mv "$TOOLS/jadx.tmp" "$TOOLS/jadx"
  chmod +x "$TOOLS/jadx/bin/jadx"
fi

# 3. Maven: the wrapper (./mvnw, committed) downloads the pinned, sha256-checked Maven distribution
#    into $MAVEN_USER_HOME (.tools/maven); artifacts go to .tools/m2 (.mvn/maven.config).
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh"
cd "$ROOT"
./mvnw -q -DskipTests dependency:go-offline >/dev/null
echo "bootstrap done. Run: source scripts/env.sh && ./mvnw test"
