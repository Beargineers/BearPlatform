#!/bin/bash
# Downloads the SDK's match logs from the robot into a new folder, and deletes them from the robot.
# Log files are renamed from .txt to .log. Run it connected to the robot's WiFi.
#
# Usage: downloadMatchLogs.sh [folder]   (default: matchLogs)

robot="http://192.168.43.1:8080"
output_dir="${1:-matchLogs}"
output_file="$output_dir/match-logs-$(date +%Y%m%d-%H%M%S).zip"
extract_dir="${output_file%.zip}"

mkdir -p "$output_dir"

echo "Downloading match logs to $output_file"
out=$(curl -sS -f -L -m 10 "$robot/bear/matchLogs" -o "$output_file" 2>&1)
rc=$?

if [ $rc -ne 0 ]; then
  rm -f "$output_file"
  echo "Downloading match logs failed with error $rc. Check that you're on the robot's WiFi."
  echo "$out"
  exit $rc
fi

mkdir -p "$extract_dir"
if ! out=$(unzip -q "$output_file" -d "$extract_dir" 2>&1); then
  rm -rf "$extract_dir"
  echo "Extracting match logs failed, the zip is kept at $output_file:"
  echo "$out"
  exit 1
fi
rm -f "$output_file"

find "$extract_dir" -type f -name "*.txt" | while read -r file; do
  mv "$file" "${file%.txt}.log"
done

echo "OK: $extract_dir"
