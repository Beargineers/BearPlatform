#!/bin/bash
# Sends a config file to the robot. It takes effect right away, even in a running opmode, and stays until the
# next install. Run it from the season project, on the robot's WiFi or over USB (see ROBOT below).
#
# Usage: updateConfig.sh [path/to/config.properties]
# Without a path, it sends the config.properties that has uncommitted changes in the current git repository.

# The robot on its WiFi; over USB, run `adb forward tcp:8080 tcp:8080` and set ROBOT=http://localhost:8080
robot="${ROBOT:-http://192.168.43.1:8080}"

cfg_path="$1"
if [ -z "$cfg_path" ]; then
  cfg_path="$(git status --porcelain | awk '$1 ~ /M/ && $2 ~ /(^|\/)config\.properties$/ {print $2; exit}')"
fi

if [ -z "$cfg_path" ]; then
  echo "No changed config.properties found. Pass the file to send: $0 path/to/config.properties"
  exit 1
fi

echo "Sending $cfg_path"
out=$(curl -sS -m 5 --fail-with-body \
  -H "Content-Type: text/plain; charset=UTF-8" \
  --data-binary "@$cfg_path" \
  "$robot/bear/config.properties" 2>&1)
rc=$?

if [ $rc -ne 0 ]; then
  echo "The robot didn't take the config:"
  echo "$out"
  echo "If this is a connection error, check that you're on the robot's WiFi, or ROBOT points to the forwarded port."
  exit $rc
fi

# The robot answers "Saved"
echo "Robot: $out"
