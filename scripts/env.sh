# Source this file from the repo root:  source scripts/env.sh
# Points everything at project-local tools in .tools/ (nothing global).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export VULNSCAN_ROOT="$ROOT"
if [ -d "$ROOT/.tools/jdk" ]; then
  JDK_HOME_DIR="$(find "$ROOT/.tools/jdk" -maxdepth 4 -type d -path '*Contents/Home' | head -1)"
  [ -z "$JDK_HOME_DIR" ] && JDK_HOME_DIR="$(find "$ROOT/.tools/jdk" -maxdepth 2 -type d -name 'jdk-*' | head -1)"   # Linux layout
  [ -n "$JDK_HOME_DIR" ] && export JAVA_HOME="$JDK_HOME_DIR"
fi
# Maven wrapper keeps its Maven distribution here instead of ~/.m2/wrapper.
export MAVEN_USER_HOME="$ROOT/.tools/maven"
# The local artifact repository (.tools/m2) is set in .mvn/maven.config.
export PATH="$ROOT/.tools/jadx/bin:${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
