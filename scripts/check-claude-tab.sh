#!/usr/bin/env bash
# The Claude Code tab beside Output on the real main window (scripts/gui-checks/.../ClaudeTabTest.java): a stand-in
# claude script in a real pseudo-terminal, then Claude Code itself when it is installed (a screenshot only).
# Needs a display; without one it uses Xvfb (xvfb-run). Screenshots go to build/claude-tab.
#
# Usage: scripts/check-claude-tab.sh [jar]
set -euo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | grep -v original | sort -V | tail -n 1)}"
OUT=build/claude-tab
HOMEDIR="$(mktemp -d)"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac -cp "$JAR" -d "$OUT/classes" scripts/gui-checks/evia/paradigma/ClaudeTabTest.java
CLAUDE="$(command -v claude || true)"
run=(java -Duser.home="$HOMEDIR" -Dout="$OUT" -Dclaude="$CLAUDE" --enable-native-access=ALL-UNNAMED
     -cp "$OUT/classes:$JAR" evia.paradigma.ClaudeTabTest)
if [ -z "${DISPLAY:-}" ]; then
  HOME="$HOMEDIR" xvfb-run -a -s "-screen 0 1600x1000x24" "${run[@]}"
else
  HOME="$HOMEDIR" "${run[@]}"
fi
