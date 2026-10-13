#!/usr/bin/env bash
# Build-prep (run by hand): regenerates the MPEG-TS fixtures in app/src/test/resources/remux/ used by the
# TS -> MP4 remux tests (TsMp4RemuxerTest). Needs an ffmpeg with libx264, aac, libmp3lame, ac3 (and libx265).
#   scripts/gen-remux-fixtures.sh /path/to/ffmpeg /path/to/output-dir
# Expected ffprobe packet counts (video/audio) are hard-coded in the tests:
#   av.ts, wrap.ts, hls-seg0-2.ts (together), hevc.ts: 125/217   disc-a.ts + disc-b.ts: 75/131 + 50/88
#   audio.ts: 217 audio   mp3.ts: 75/116   aac-ac3.ts: 50 video, 95 AAC, 63 AC-3
set -euo pipefail
FFMPEG=${1:?ffmpeg binary}
OUT=${2:?output dir}
mkdir -p "$OUT"
cd "$OUT"

V5=(-f lavfi -i "testsrc2=size=320x240:rate=25:duration=5")
A5=(-f lavfi -i "sine=frequency=440:sample_rate=44100:duration=5")
H264=(-c:v libx264 -preset veryfast -profile:v main -g 25 -bf 2 -b:v 150k -pix_fmt yuv420p)
AAC=(-c:a aac -b:a 64k -ac 2)
q=(-hide_banner -loglevel error -y)

# (1) 5 s H.264 + AAC, key frame every second, B-frames
"$FFMPEG" "${q[@]}" "${V5[@]}" "${A5[@]}" "${H264[@]}" "${AAC[@]}" -shortest -f mpegts av.ts

# (2) the same content as 2 s HLS segments (2 s, 2 s, 1 s); the test concatenates them like the downloader does
"$FFMPEG" "${q[@]}" "${V5[@]}" "${A5[@]}" "${H264[@]}" "${AAC[@]}" -shortest \
  -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_type mpegts -hls_segment_filename hls-seg%d.ts hls-index.m3u8
rm hls-index.m3u8

# (3) starts 0.3 s before the 33-bit PTS wrap (2^33 / 90000 = 95443.7 s): the clock wraps inside the clip
"$FFMPEG" "${q[@]}" "${V5[@]}" "${A5[@]}" "${H264[@]}" "${AAC[@]}" -shortest -output_ts_offset 95442 -f mpegts wrap.ts

# (4) a timeline reset in the middle (EXT-X-DISCONTINUITY): 3 s, then another 2 s encode that starts at 0 again
"$FFMPEG" "${q[@]}" -f lavfi -i "testsrc2=size=320x240:rate=25:duration=3" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=3" \
  "${H264[@]}" "${AAC[@]}" -shortest -f mpegts disc-a.ts
"$FFMPEG" "${q[@]}" -f lavfi -i "testsrc2=size=320x240:rate=25:duration=2" -f lavfi -i "sine=frequency=660:sample_rate=44100:duration=2" \
  "${H264[@]}" "${AAC[@]}" -shortest -f mpegts disc-b.ts

# (5) audio only, AAC in ADTS inside TS
"$FFMPEG" "${q[@]}" -f lavfi -i "sine=frequency=330:sample_rate=44100:duration=5" -ac 2 -c:a aac -b:a 48k -f mpegts audio.ts

# (6) H.265 + AAC
"$FFMPEG" "${q[@]}" "${V5[@]}" "${A5[@]}" -c:v libx265 -preset veryfast -g 25 -b:v 120k -pix_fmt yuv420p \
  -x265-params log-level=error -tag:v hvc1 "${AAC[@]}" -shortest -f mpegts hevc.ts

# (7) H.264 + MP3 (3 s)
"$FFMPEG" "${q[@]}" -f lavfi -i "testsrc2=size=320x240:rate=25:duration=3" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=3" \
  -c:v libx264 -preset veryfast -profile:v main -g 25 -bf 2 -b:v 100k -pix_fmt yuv420p -c:a libmp3lame -b:a 64k -ac 2 -shortest -f mpegts mp3.ts

# (8) H.264 + AC-3 only (cannot be copied into this MP4 path), and H.264 + AAC + AC-3 (AC-3 is left out)
"$FFMPEG" "${q[@]}" -f lavfi -i "testsrc2=size=320x240:rate=25:duration=2" -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2" \
  -c:v libx264 -preset veryfast -profile:v main -g 25 -bf 2 -b:v 100k -pix_fmt yuv420p -c:a ac3 -b:a 96k -ac 2 -shortest -f mpegts ac3.ts
"$FFMPEG" "${q[@]}" -f lavfi -i "testsrc2=size=320x240:rate=25:duration=2" -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2" \
  -f lavfi -i "sine=frequency=880:sample_rate=48000:duration=2" -map 0:v -map 1:a -map 2:a \
  -c:v libx264 -preset veryfast -profile:v main -g 25 -bf 2 -b:v 100k -pix_fmt yuv420p -c:a:0 aac -b:a:0 48k -c:a:1 ac3 -b:a:1 96k -ac 2 \
  -shortest -f mpegts aac-ac3.ts
ls -l
