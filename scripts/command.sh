#!/bin/bash
# Starts a command the running opmode offers, like a button on the robot's graphs page. Someone at the Driver
# Station should be ready to stop the robot. Run it on the robot's WiFi or over USB (see ROBOT below).
#
# Usage: command.sh "command name"
# Without a name, it lists the commands offered now.

# The robot on its WiFi; over USB, run `adb forward tcp:8080 tcp:8080` and set ROBOT=http://localhost:8080
robot="${ROBOT:-http://192.168.43.1:8080}"

if [ -z "$1" ]; then
  out=$(curl -sS -m 5 "$robot/bear/graphs/data?since=999999999") || exit $?
  echo "Commands offered now:"
  echo "$out" | sed -n 's/.*"commands":\[\(.*\)\],"series".*/\1/p' | grep -o '"name":"[^"]*"' | sed 's/"name":"\(.*\)"/  \1/'
  exit 0
fi

out=$(curl -sS -m 5 --fail-with-body -X POST --data-urlencode "name=$1" "$robot/bear/command" 2>&1)
rc=$?
if [ $rc -ne 0 ]; then
  echo "The robot didn't start it:"
  echo "$out"
  exit $rc
fi
echo "Robot: $out"
