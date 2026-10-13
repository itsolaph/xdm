#!/usr/bin/env bash
# Build-prep: fetch the StevenBlack unified hosts list (MIT) and convert it to the compact asset the app bundles:
#   app/src/main/assets/blocklist.txt.gz   (gzip, one domain per line, sorted, first line = "# xdm-blocklist updated=<epoch ms>")
# Usage: scripts/update-blocklist.sh [path/to/downloaded/hosts]
# Without an argument the list is downloaded into build/blocklist-src/ first.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src_dir="$root/build/blocklist-src"
out="$root/app/src/main/assets/blocklist.txt.gz"
url="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

hosts="${1:-}"
if [ -z "$hosts" ]; then
  mkdir -p "$src_dir"
  hosts="$src_dir/hosts"
  curl -fsSL --max-time 120 -o "$hosts" "$url"
fi

# "# Date: 07 October 2026 18:34:19 (UTC)" -> epoch milliseconds (falls back to now)
date_line="$(grep -m1 '^# Date:' "$hosts" | sed 's/^# Date: *//; s/ *(UTC)//' || true)"
if [ -n "$date_line" ] && secs="$(date -u -d "$date_line" +%s 2>/dev/null)"; then
  millis=$((secs * 1000))
else
  millis=$(( $(date -u +%s) * 1000 ))
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

# Same rules as HostsParser: drop comments, keep hostnames after an IP, skip localhost-style names and IPs.
tr -d '\r' < "$hosts" | sed 's/#.*//' | awk '
  NF >= 2 && $1 ~ /^(0\.0\.0\.0|127\.0\.0\.1)$/ {
    for (i = 2; i <= NF; i++) {
      h = tolower($i); sub(/\.$/, "", h)
      if (h ~ /^[a-z0-9_-]+(\.[a-z0-9_-]+)+$/ && h !~ /\.[0-9]+$/ && h != "localhost.localdomain") print h
    }
  }' | LC_ALL=C sort -u > "$tmp"

count="$(wc -l < "$tmp")"
if [ "$count" -lt 1000 ]; then
  echo "refusing to write: only $count domains parsed" >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
{ echo "# xdm-blocklist updated=$millis"; cat "$tmp"; } | gzip -9 -n > "$out"
echo "wrote $out: $count domains, $(wc -c < "$out") bytes gzipped (list date $date_line)"
