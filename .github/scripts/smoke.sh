#!/usr/bin/env bash
# Emulator smoke test, run by .github/workflows/android.yml (job "smoke") inside
# reactivecircus/android-emulator-runner. Installs the debug APK, opens the app, taps through
# every bottom-navigation item it can find, checks dark mode, runs the YouTube end-to-end self-test (debug-only
# activity; BLOCKED by YouTube is not a failure), then lets monkey poke at it.
# Output in $OUT: numbered screenshots, result.txt (PASS/FAIL + reasons), crash.txt if anything died.
# Usage: smoke.sh <package> <apk>
set -u
PKG="$1"; APK="$2"; OUT="${OUT:-smoke-out}"
mkdir -p "$OUT"; : > "$OUT/result.txt"
n=0
shot() { n=$((n+1)); adb exec-out screencap -p > "$OUT/$(printf '%02d' $n)-$1.png"; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }
note() { echo "$*" | tee -a "$OUT/result.txt"; }
fail=0

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# let the freshly booted system settle, otherwise its own startup work causes ANRs
sleep 45
adb logcat -c
adb install -r -g "$APK" >/dev/null || { note "FAIL install"; exit 1; }

launch() {
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  for _ in $(seq 1 20); do alive && break; sleep 1; done
  sleep 6
}

launch
if alive; then note "ok   cold start"; else note "FAIL cold start: process not running"; fail=1; fi
shot launch

# Bottom navigation: clickable nodes in the lowest 14% of the screen, left to right.
H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$OUT/ui-home.xml" >/dev/null 2>&1
python3 - "$OUT/ui-home.xml" "$H" > "$OUT/nav.txt" <<'EOF'
import re, sys, xml.etree.ElementTree as ET
path, h = sys.argv[1], int(sys.argv[2])
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
seen = []
for node in root.iter("node"):
    if node.get("clickable") != "true":
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    if y1 < h * 0.86 or x2 - x1 < 40:
        continue
    label = node.get("text") or node.get("content-desc") or ""
    if not label:
        for child in node.iter("node"):
            label = child.get("text") or child.get("content-desc") or ""
            if label:
                break
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    if any(abs(cx - s[0]) < 20 for s in seen):
        continue
    seen.append((cx, cy, label))
for cx, cy, label in sorted(seen)[:6]:
    print(cx, cy, re.sub(r"[^A-Za-z0-9]+", "-", label).strip("-")[:20] or "tab")
EOF
# adb reads stdin, so feed it /dev/null or it swallows the rest of nav.txt
while read -r x y label; do
  [ -z "${x:-}" ] && continue
  adb shell input tap "$x" "$y" </dev/null; sleep 3
  if alive </dev/null; then note "ok   tab $label"; else note "FAIL tab $label: app died"; fail=1; launch </dev/null; fi
  shot "tab-$label" </dev/null
done < "$OUT/nav.txt"
[ -s "$OUT/nav.txt" ] || note "info no bottom navigation found"

adb shell cmd uimode night yes; launch
if alive; then note "ok   dark mode"; else note "FAIL dark mode start"; fail=1; fi
shot dark
adb shell cmd uimode night no

launch
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
if alive; then note "ok   resume from background"; else note "FAIL resume"; fail=1; fi

# YouTube end-to-end (debug-only YoutubeTestActivity): resolves a Creative Commons video with the extractor, downloads the
# smallest video-only MP4 and audio in 1 MB range chunks, merges them with media3 and reads the result back with the
# platform's MediaExtractor. It logs one line, which goes verbatim into result.txt:
#   YTTEST PASS <w>x<h> <duration>s tracks=2
#   YTTEST BLOCKED <reason>   YouTube refused this runner (bot check, sign-in, region, no route): says nothing about the app
#   YTTEST FAIL <reason>      only FAIL (or no line at all within 180 s) fails the job
yt_id="${YTTEST_ID:-aqz-KE-bpKQ}"
adb shell am start -n "$PKG/.debug.YoutubeTestActivity" --es id "$yt_id" >/dev/null 2>&1
yt_line=""
yt_deadline=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$yt_deadline" ]; do
  yt_line=$(adb logcat -d -s YTTEST:I 2>/dev/null | tr -d '\r' | sed -nE 's/^.*(YTTEST (PASS|FAIL|BLOCKED).*)$/\1/p' | tail -1)
  [ -n "$yt_line" ] && break
  sleep 2
done
shot youtube
adb logcat -d -s YTTEST:V 2>/dev/null | tr -d '\r' > "$OUT/youtube-log.txt" || true
case "$yt_line" in
  "YTTEST PASS"*|"YTTEST BLOCKED"*) note "$yt_line" ;;
  "YTTEST FAIL"*) note "$yt_line"; fail=1 ;;
  *) note "YTTEST FAIL no result within 180 s"; fail=1 ;;
esac
launch </dev/null  # back to the main screen (this also stops the test activity) before the monkey run

# Random UI poking. Network is not mocked, so this is about crashes, not features.
adb shell monkey -p "$PKG" --throttle 250 --pct-syskeys 0 --pct-appswitch 0 -s 4242 -v 300 > "$OUT/monkey.txt" 2>&1
if grep -qE "(CRASH|NOT RESPONDING): $PKG|ANR in $PKG" "$OUT/monkey.txt"; then note "FAIL monkey (300 events): $(grep -oE "(CRASH|NOT RESPONDING): $PKG|ANR in $PKG.*" "$OUT/monkey.txt" | head -1)"; fail=1; else note "ok   monkey 300 events"; fi
grep -E -A3 "ANR in $PKG" "$OUT/monkey.txt" | grep -m1 Reason: | sed 's/^/     /' >> "$OUT/result.txt" || true
alive && shot after-monkey

adb logcat -d -b crash 2>/dev/null | grep -B2 -A40 "Process: $PKG" > "$OUT/crash.txt" || true
adb logcat -d '*:E' | grep -E "FATAL EXCEPTION|AndroidRuntime|$PKG" | tail -200 > "$OUT/errors.txt"
if [ -s "$OUT/crash.txt" ]; then note "FAIL crash buffer not empty (crash.txt)"; fail=1; else rm -f "$OUT/crash.txt"; fi
rm -f "$OUT/ui-home.xml"

if [ $fail -eq 0 ]; then sed -i '1i PASS' "$OUT/result.txt"; else sed -i '1i FAIL' "$OUT/result.txt"; fi
cat "$OUT/result.txt"
exit 0
