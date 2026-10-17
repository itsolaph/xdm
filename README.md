# XDM

A lightweight download manager for Android, in the spirit of 1DM and IDM+. Written in Kotlin with Jetpack Compose (Material 3), OkHttp, Room and DataStore.

## Fair use

XDM is for downloading files and videos you're **allowed** to download: your own, freely licensed (Creative Commons, public domain), or shared with the owner's permission. Don't use it to download or redistribute copyrighted content without permission.

- XDM does **not** open or get around DRM, and that won't be added. Protected streams (Widevine, FairPlay, PlayReady) are refused.
- Some services, YouTube included, forbid downloading through third-party apps in their terms of service. Following those terms and the copyright law where you live is up to you.
- This repo only contains source code. No official APKs are distributed from here.
- The software comes "as is", without any warranty (see [LICENSE](LICENSE), sections 15–16).

## Features

### Downloads
- **Multiple connections**: a file is split into 1–16 segments (8 by default) using `Range` requests and written into one temp file. Files under 1 MB use a single connection.
- **Dynamic segment splitting**: when a connection finishes its segment, it takes over half of the segment that has the most left to fetch (as long as at least 2 MB of it is left). A slow connection near the end no longer holds up the whole file, and there are never more connections than the setting allows. Only for servers with `Range` support and a known size; HLS and single-connection downloads aren't split.
- **Resume**: per-segment progress is saved to Room, split segments included, so pausing, a force-stopped app or a reboot doesn't lose work. On resume, ETag / Last-Modified / size are checked and the download restarts from zero if the file changed. Servers without `Range` support or a known size get one connection and are marked as not resumable.
- **Resume after restart** (Settings, on by default): downloads that were running or queued when the phone switched off, or when XDM was updated, go back to the queue; ones you paused stay paused, and "Wi-Fi only" still applies. Up to Android 14 they continue on their own after a reboot. Android 15 and later don't let an app start a download service at boot, so XDM posts a "downloads ready to resume" notification instead, and they continue when you tap Resume or open the app. After an app update they continue on their own on every version.
- Per-segment retries with exponential backoff for I/O errors and 5xx; 4xx fails right away (except 408/429).
- File names come from `Content-Disposition` (including RFC 5987 `filename*`), then the URL path, then the MIME type. Names are sanitized and collisions become `name (1).ext`.
- **Queue** with a parallel limit (3 by default), pause / resume / cancel / retry per item, pause all / resume all, and "Start now" to skip the limit.
- Foreground service with a summary notification plus per-download controls. It stops by itself when nothing is running.
- **Wi-Fi only** option, which waits while the network is metered.
- Finished files go to `Download/XDM/` (MediaStore on Android 10+), or into the private folder (below).
- **Link capture**: share a URL to XDM, use "Open with" on download-type links (apk, zip, mp4, pdf, iso, …), or accept the offer for a URL on the clipboard.
- Bulk delete per tab and a multi-select mode, optionally removing the files from storage too.
- Tapping a finished file opens it with another app ("Open with…" in the item's menu always asks which). XDM has no media player of its own.
- Finished videos show a thumbnail in the list (a frame picked once and cached as a small JPEG in the app's cache).

### Private folder
- A locked folder for downloads you don't want in the gallery. The lock icon in the Downloads screen opens it.
- A 4–8 digit PIN, set on first use. Only a salted PBKDF2-HMAC-SHA256 hash (150,000 rounds) is stored, never the PIN. After 5 wrong PINs there's a 30 s pause, which doubles with every further wrong PIN (up to an hour). Fingerprint or face unlock can be switched on in the folder's menu; the PIN always works too. "Change PIN" needs the old one.
- Locks itself when XDM goes to the background (immediately, after 1 minute or after 5 minutes; Settings) and whenever the screen turns off.
- **Forgotten PIN**: there is no recovery. "Reset private folder" (on the PIN screen) deletes every private file, private downloads still in progress, and the PIN, after you type `RESET`.
- "Save to private folder" in the add sheet, the browser's video sheet and the YouTube sheet (default: Settings → "Save new downloads to private folder"). Private downloads run like any other; when they finish, the file moves into the private folder instead of `Download/XDM`.
- Finished public downloads can be moved in ("Move to private folder" in the item's menu): the file is copied, the size is checked, then the public copy is deleted (through the system's confirmation dialog if XDM didn't create it). If the copy fails, nothing changes; if the public copy can't be deleted, both stay and XDM tells you.
- Inside: thumbnails, size and duration; tap to open with another app (after a warning); Move out (back to `Download/XDM`), Rename, Share (with a warning that the copy leaves the folder), Delete, multi-select and "Delete all".
- Private items never appear in the main Downloads list, locked or not; they only show inside the private folder (unfinished ones too, with pause/resume/cancel). The main list's "Delete all" and multi-select can't reach them.
- Notifications for private downloads say "Private download" without file name, error text or preview, are hidden on the lock screen, and open the private folder (which asks for the PIN), never the file.
- Screenshots, screen recording and the recents thumbnail are blocked while the private folder is on screen.

**How private is it?** The files live in XDM's app-internal storage (`files/private/`). Android's app sandbox keeps other apps, file managers, the gallery and MediaStore out, and the device's file-based encryption protects them while the phone is locked. There is **no extra encryption by XDM**: anyone with root access to the phone (or a tool that copies app data with root) can read the files. The PIN only guards XDM's own screens. The folder and the PIN are excluded from cloud backups and device-to-device transfers. While a private download is still running, its partial data is in XDM's temporary folder (app-specific storage, not indexed by the gallery); it only moves into the private folder when it's finished. Thumbnails of private videos are kept in XDM's internal cache and deleted with the item or by a reset. Uninstalling XDM deletes the private folder.

### Built-in browser
- Tabs, incognito tabs, bookmarks, history, a speed-dial home page, desktop mode, find in page, and WebView dark mode where supported.
- Downloads started in the browser open XDM's "Add download" sheet instead of the system downloader. The page's cookie, User-Agent and Referer are carried along and kept across pause/resume and app restarts.
- Long-press a link, image or video for "Download with XDM", "Open in new tab", "Copy link" and "Share link".
- **Fullscreen video**: a video's fullscreen button fills the screen, hides the system bars, keeps the screen on and allows landscape. Rotating doesn't reload the page. Back, switching tabs or leaving the browser ends it.

### Video grabber
- Each tab watches network requests and the page DOM (`<video>`, `<source>`, `og:video`, `MutationObserver`, `play` events) for media: mp4, webm, mkv, mp3, m4a, m3u8, mpd and so on. Candidates are checked in the background to confirm type and size.
- A floating button shows what was found: type, quality, size (estimated for HLS), and the item that's currently playing at the top. HLS master playlists list every variant and separate audio tracks.
- **HLS downloads** (VOD): master and media playlists, `BYTERANGE`, `MAP`, AES-128 keys, segments downloaded in parallel and resumable per segment.
- **HLS to MP4**: MPEG-TS output is copied into a real MP4 without re-encoding (H.264/H.265 + AAC or MP3) using `androidx.media3`, so it plays and seeks in any gallery app. Timestamp jumps and discontinuities are stitched into one timeline. If conversion fails, the `.ts` is kept with a note.

### YouTube
- Uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), no yt-dlp or Python. The version is one line in `gradle/libs.versions.toml` (`newpipeExtractor`); when YouTube changes something, bump it.
- Works from the share sheet, the "Add download" field, the clipboard offer and the built-in browser. Accepts `watch`, `youtu.be`, `shorts`, `embed`, `live` replays, `music.youtube.com` and playlists (up to 200 videos).
- Choose a resolution (DASH video + best AAC audio, merged into one MP4 without re-encoding), a quick 360p single file, or audio only (M4A). A default quality can be set in Settings.
- Stream URLs aren't stored, only the video id and format. Expired URLs (403/410) are refreshed and the download carries on from the bytes already on disk.
- Errors are explained in plain words: age-restricted, private, removed, members-only, live, region-blocked, bot check, or "YouTube changed something; XDM needs an update."

### Ad blocking
- A pure Kotlin filter engine for a subset of EasyList / AdGuard / uBlock Origin syntax: network rules with the common options (`third-party`, `domain=`, resource types, `important`, `badfilter`, `@@` exceptions, page-level exceptions) and cosmetic rules, both site-specific and generic.
- Bundled lists: EasyList, AdGuard Base, AdGuard DNS filter and StevenBlack hosts, each switchable in Settings and updatable from inside the app. Third-party checks use the bundled Public Suffix List.
- Pop-up blocking (off / ads only / all) and protection against redirect click-jacking.
- A shield icon shows how much was blocked on the page, with a per-site allowlist.
- Blocking runs before the video grabber, so ad streams never show up in the video list. Downloads you start yourself are never blocked.

## Building

You need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). Create `local.properties`:

```
sdk.dir=/path/to/android-sdk
```

Then:

```
./gradlew testDebugUnitTest assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`. To install it, allow "Install unknown apps" for whatever app opens the file. On Android 13+, allow notifications when asked.

Builds are signed with the default Android debug key, which differs per machine, so an APK built elsewhere can't update one that's already installed. Use your own keystore through `private.properties` (below) if you want a stable signature.

### Optional build config

Everything here is optional; the build works without it. Put the keys in `private.properties` at the repo root (gitignored, as is `private/`) or pass them as `-Pxdm.<key>=…`. `-P` wins if both are set.

| Key | What it does |
|---|---|
| `crashRelayUrl` | Endpoint for crash reports. Empty means crash reporting is off: nothing is written or sent. |
| `crashRelayKey` | Value of the `X-App-Key` header for that endpoint. |
| `signingStoreFile` | Keystore for signing debug and release builds (relative to the repo root, or absolute). Empty means the default debug key. |
| `signingStorePassword` | Keystore password. |
| `signingKeyAlias` | Key alias. |
| `signingKeyPassword` | Key password. |
| `privateRulesFile` | Extra filter rules (EasyList syntax), copied into the `xdm-private.txt` asset and merged into the `xdm-extra` list. |

## Project layout

- `engine/`: the download core, plain Kotlin/JVM with no Android APIs. Probing, segment splitting, the multi-connection engine, the M3U8 parser and the HLS engine. Tested against `MockWebServer`.
- `media/`: video detection (URL classification, per-tab collection, probing, HLS quality options).
- `adblock/` and `adblock/filters/`: the filter engine, list loading, binary cache and updates.
- `remux/`: MPEG-TS to MP4 and the DASH merger for YouTube.
- `youtube/`: link parsing, format selection, error messages, the NewPipeExtractor bridge.
- `core/`: `DownloadManager` (queue, pause/resume, Wi-Fi only, moves into and out of the private folder), storage, network monitor.
- `vault/`: the private folder's storage, PIN hashing, wrong-PIN cooldown and the lock state machine.
- `data/`: Room and DataStore.
- `service/`: foreground service and notifications.
- `browser/`: tabs, WebView, download capture, the injected scripts.
- `ui/`: Compose screens.

Room schemas are exported to `app/schemas/` and committed. `MigrationTest` runs every migration on a populated database and compares the result with the exported schema. There are no destructive migrations.

CI runs the unit tests, a build and lint, then a smoke test on an emulator. That includes a real YouTube download of *Big Buck Bunny*, which may come back BLOCKED when YouTube rejects the runner's IP, and the private folder self-test: debug builds contain `VaultTestActivity` (`adb shell am start -n com.olaph.xdm/.debug.VaultTestActivity`), which puts a bundled 10 s test video into the private folder, checks MediaStore can't see it, reads its duration and decodes a frame with `MediaMetadataRetriever`, deletes it again and logs one `VAULTTEST PASS …` or `VAULTTEST FAIL …` line.

## Known limitations

- Moving a finished file into `Download/XDM` copies it, so it briefly needs about twice the space.
- On Android 15+, downloads interrupted by a reboot wait for a tap on the notification (or for the app to be opened); see "Resume after restart".
- **Video**: no DRM (by design). Live HLS isn't downloaded. DASH (`.mpd`) outside YouTube is detected but not downloadable yet. `blob:` players only work when the real manifest shows up in network traffic. HLS to MP4 supports H.264/H.265 with AAC/MP3; other codecs keep the `.ts`. Detection is heuristic, so false hits and misses happen.
- **YouTube**: depends on NewPipeExtractor keeping up with YouTube. No account login, so age-restricted, members-only, paid and private videos can't be downloaded. Live streams, premieres and channels aren't supported. Video comes as H.264 up to 1080p, or AV1 above that; VP9/WebM isn't offered because media3's MP4 muxer rejects it. Audio is always AAC. No subtitles or chapters. Datacenter and VPN IPs often hit the bot check.
- **Ad blocking**: scriptlets (`+js()`), procedural selectors, `$replace`, `csp`, `removeparam` and similar rules aren't supported and are skipped. Sites that need scriptlets, YouTube's video ads among them, still show ads. `redirect=` blocks instead of serving a stub. Request types are inferred because WebView doesn't report them. Lists use about 27 MB of heap, and the first compile after an install or update takes a few seconds.
- Saved cookies aren't refreshed, so an expired session means re-adding the download from the browser.
- **Private folder**: see "How private is it?" above. A move into the folder needs free space for a second copy for a moment. Opening a private file in another app or sharing one lets that app read it (and possibly keep a copy).
- Browser: `blob:`/`data:` downloads can't be captured, tabs aren't restored after the process is killed, location and camera are always denied.

## License

XDM is released under the **GNU GPL v3.0 or later** (see [LICENSE](LICENSE)), since it uses NewPipeExtractor, which is GPL as well.

## Credits

Libraries, all pulled unmodified from Maven Central / JitPack:

| Library | Source | License |
|---|---|---|
| NewPipeExtractor | https://github.com/TeamNewPipe/NewPipeExtractor | GPL-3.0-or-later |
| NewPipeExtractor dependencies: nanojson, jsoup, protobuf-javalite, Rhino | via NewPipeExtractor | nanojson's own license, MIT, BSD-3-Clause, MPL-2.0 |
| OkHttp / Okio | https://github.com/square/okhttp | Apache-2.0 |
| AndroidX: Jetpack Compose, Material 3, Room, DataStore, WebKit, Lifecycle (incl. lifecycle-process), Fragment, Biometric | https://developer.android.com/jetpack/androidx | Apache-2.0 |
| AndroidX Media3 (extractor, muxer) | https://github.com/androidx/media | Apache-2.0 |
| Kotlin, kotlinx.coroutines | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| desugar_jdk_libs | https://github.com/google/desugar_jdk_libs | GPL-2.0 with Classpath Exception |

Tests use JUnit 4 (EPL-1.0), Robolectric (MIT), MockWebServer (Apache-2.0) and sqlite-jdbc (Apache-2.0). The YouTube test video is *Big Buck Bunny* (© Blender Foundation, CC BY 3.0). The private folder self-test video (debug builds only) is a synthetic test pattern and tone generated with FFmpeg.

The bundled filter lists are separate works under their own licenses. They're only trimmed (comments and rule types XDM can't use are removed), never edited. Full license texts are in `app/src/main/assets/licenses/` and readable in the app under Settings → "About & licenses".

| List | Source | License | Snapshot |
|---|---|---|---|
| EasyList | https://easylist.to/easylist/easylist.txt | GPLv3 / CC BY-SA 3.0 (dual) | 09 Oct 2026 18:18 UTC |
| AdGuard Base (without EasyList, uBO variant) | https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt | GPLv3 | 2026-10-09T17:05:11Z |
| AdGuard DNS filter (domain rules only) | https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt | GPLv3 | 2026-10-09T18:18:53Z |
| StevenBlack/hosts (unified) | https://github.com/StevenBlack/hosts | MIT (© Steven Black) | 2026-10-07 |
| Public Suffix List | https://publicsuffix.org/list/public_suffix_list.dat | MPL-2.0 | 2026-10-10 |

`licenses/filter-lists-NOTICE.txt` summarizes sources, licenses and dates. The built-in `xdm-extra.txt` list is part of XDM.
