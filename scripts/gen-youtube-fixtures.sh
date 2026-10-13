#!/usr/bin/env bash
# Build-prep (run by hand): regenerates the tiny DASH-style fixtures in app/src/test/resources/youtube/ used by
# Mp4DashMuxerTest. They imitate what YouTube serves for adaptive formats: video-only and audio-only files, each a
# fragmented MP4 (global sidx, one fragment per key frame) or WebM. Needs an ffmpeg with libx264, aac, libvpx-vp9,
# libsvtav1 and libopus.
#   scripts/gen-youtube-fixtures.sh /path/to/ffmpeg /path/to/output-dir
# Content (the tests hard-code it): video 192x108 25 fps 3 s (video-avc.mp4, video-vp9.webm, video-av1.mp4),
# video-avc-long.mp4 is 5 s, audio 3 s (audio-aac.m4a, audio-opus.webm), audio-aac-short.m4a is 2 s.
set -euo pipefail
FFMPEG=${1:?ffmpeg binary}
OUT=${2:?output dir}
mkdir -p "$OUT"
cd "$OUT"

q=(-hide_banner -loglevel error -y)
V3=(-f lavfi -i "testsrc2=size=192x108:rate=25:duration=3")
V5=(-f lavfi -i "testsrc2=size=192x108:rate=25:duration=5")
A3=(-f lavfi -i "sine=frequency=440:sample_rate=44100:duration=3")
A2=(-f lavfi -i "sine=frequency=660:sample_rate=44100:duration=2")
FRAG=(-movflags +dash+frag_keyframe+empty_moov+default_base_moof -global_sidx 1 -f mp4)

# H.264 (what YouTube calls avc1), B-frames, a key frame every second
"$FFMPEG" "${q[@]}" "${V3[@]}" -an -c:v libx264 -preset veryfast -profile:v main -g 25 -bf 2 -b:v 80k -pix_fmt yuv420p "${FRAG[@]}" video-avc.mp4
"$FFMPEG" "${q[@]}" "${V5[@]}" -an -c:v libx264 -preset veryfast -profile:v main -g 25 -bf 2 -b:v 80k -pix_fmt yuv420p "${FRAG[@]}" video-avc-long.mp4
# AAC-LC audio as M4A (itag 140 style)
"$FFMPEG" "${q[@]}" "${A3[@]}" -vn -c:a aac -b:a 48k -ac 2 "${FRAG[@]}" audio-aac.m4a
"$FFMPEG" "${q[@]}" "${A2[@]}" -vn -c:a aac -b:a 48k -ac 2 "${FRAG[@]}" audio-aac-short.m4a
# VP9 in WebM, AV1 in MP4 (the formats YouTube uses above 1080p)
"$FFMPEG" "${q[@]}" "${V3[@]}" -an -c:v libvpx-vp9 -b:v 60k -g 25 -deadline realtime -cpu-used 8 -pix_fmt yuv420p -f webm video-vp9.webm
"$FFMPEG" "${q[@]}" "${V3[@]}" -an -c:v libsvtav1 -preset 10 -g 25 -b:v 60k -pix_fmt yuv420p "${FRAG[@]}" video-av1.mp4
# Opus in WebM: audio XDM does not mux (must be rejected with a clear message)
"$FFMPEG" "${q[@]}" "${A3[@]}" -vn -c:a libopus -b:a 32k -f webm audio-opus.webm
ls -l
