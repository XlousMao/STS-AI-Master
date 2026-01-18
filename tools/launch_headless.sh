#!/bin/bash
# STS-AI-Master Headless Launch Script
# Usage: ./launch_headless.sh [PORT]
# Example: ./launch_headless.sh 9999

PORT=${1:-9999}
MTS_JAR="libs/ModTheSpire.jar"
STEAM_PATH="D:/Games/Steam/steamapps/common/SlayTheSpire"

echo "[STS-AI] Launching Slay the Spire in HEADLESS mode on port $PORT..."

# Note: We assume libs/ModTheSpire.jar exists locally or use the one in Steam path
# If local libs are used:
java -Dsts.ai.port=$PORT -jar $MTS_JAR --headless --mods "basemod,stslib,sts-ai-bridge"

# If using Steam path directly (adjust as needed):
# java -Dsts.ai.port=$PORT -jar "$STEAM_PATH/ModTheSpire.jar" --headless --mods "basemod,stslib,sts-ai-bridge"
