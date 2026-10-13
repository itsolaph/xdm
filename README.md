# XDM — Download Manager untuk Android

Download manager ringan (mirip 1DM / IDM+) berbasis Kotlin, Jetpack Compose (Material 3), OkHttp, Room, dan DataStore.

## Penggunaan yang wajar

XDM adalah alat untuk mengunduh berkas dan video yang **boleh kamu unduh**: milikmu sendiri, berlisensi bebas (Creative Commons, domain publik), atau yang izinnya diberikan pemiliknya. Jangan memakainya untuk mengunduh atau menyebarkan konten berhak cipta tanpa izin.

- XDM **tidak** membuka atau melewati DRM, dan tidak akan ditambahkan. Stream berproteksi (Widevine, FairPlay, PlayReady) ditolak.
- Beberapa layanan, termasuk YouTube, melarang pengunduhan lewat aplikasi pihak ketiga di ketentuan layanannya. Kamu yang bertanggung jawab mematuhi ketentuan layanan dan hukum hak cipta di tempatmu.
- Repo ini hanya berisi kode sumber; tidak ada APK resmi yang dibagikan dari sini.
- Perangkat lunak ini diberikan "apa adanya", tanpa jaminan apa pun (lihat [LICENSE](LICENSE), bagian 15–16).

## Fitur

- **Multi-koneksi**: file dipecah jadi 1–16 segmen (default 8) dengan header `Range`, ditulis ke satu file sementara. File < 1 MB memakai 1 koneksi.
- **Resume**: progres per segmen disimpan ke Room (±500 ms / 1 MB), jadi pause, app dimatikan paksa, atau reboot bisa dilanjutkan. Saat lanjut, ETag / Last-Modified / ukuran divalidasi; kalau berubah, unduhan diulang dari nol.
- Server tanpa dukungan `Range` atau tanpa ukuran → 1 koneksi, **tidak bisa di-resume** (ditandai di UI).
- Retry per segmen dengan exponential backoff (5x) untuk error IO / 5xx; gagal langsung untuk 4xx (kecuali 408/429 yang di-retry).
- Nama file dari `Content-Disposition` (termasuk `filename*` RFC 5987) → path URL → MIME type; disanitasi dan anti-tabrakan (`nama (1).ext`).
- **Antrian**: QUEUED / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED, batas unduhan paralel (default 3), pause/resume/cancel/retry per item, "pause all" / "resume all", "Start now" melewati batas paralel.
- **Foreground service** (`dataSync`): notifikasi ringkasan + notifikasi per unduhan (Pause/Resume/Cancel), notifikasi selesai yang membuka file. Service berhenti sendiri saat tidak ada unduhan aktif.
- Opsi **Wi-Fi only**: menunggu bila jaringan metered.
- File selesai dipindah ke `Download/XDM/` lewat MediaStore (API 29+) atau `Download/XDM/` langsung (API 26–28).
- **Intercept link**: Share teks berisi URL ke XDM, "Open with" untuk link http(s) bertipe/berekstensi unduhan (apk, zip, rar, 7z, mp4, mkv, mp3, pdf, iso, …), dan tawaran otomatis untuk URL di clipboard (sekali per URL). Dialog tambah unduhan muncul transparan di atas aplikasi asal.
- **Browser bawaan** (tab bawah "Downloads" / "Browser", `WebView` di Compose):
  - Address bar URL **atau kata kunci** (mesin pencari: Google / DuckDuckGo / Bing di Settings), ikon gembok HTTPS, progress bar, back / forward / reload / stop / home; tombol back sistem menelusuri riwayat WebView dulu.
  - **Banyak tab** (badge jumlah tab), tab baru, tutup tab, **tab incognito** (tanpa riwayat; cookie dihapus saat tab incognito terakhir ditutup — lihat keterbatasan), pemblokir pop-up (lihat "Pemblokir iklan").
  - Halaman beranda: speed-dial dari bookmark + riwayat terbaru. **Bookmark** dan **Riwayat** (Room, dengan `Migration` 1→2 — data unduhan lama tetap aman).
  - Menu: share halaman, salin link, buka di browser eksternal, mode desktop (ganti User-Agent), cari di halaman. Upload file (`<input type=file>`), `intent://`, `market://`, `tel:`, `mailto:` dibuka aman di aplikasi lain (hanya dengan gesture); lokasi/kamera ditolak. Mode gelap WebView (algorithmic darkening) bila didukung.
  - Tab dan halaman **tidak dimuat ulang** saat pindah ke Downloads lalu kembali, atau saat layar diputar.
- **Intercept unduhan dari browser**: `DownloadListener` membuka sheet "Add download" yang sudah terisi (URL, nama dari `Content-Disposition` + MIME, ukuran, asal halaman) alih-alih downloader sistem. **Cookie**, **User-Agent**, dan **Referer** dari halaman ikut dikirim pada probe dan setiap request segmen — juga setelah pause/resume/restart aplikasi (disimpan di Room). Tekan lama link/gambar → "Download with XDM", "Open in new tab", "Copy link", "Share link". Opsi "Intercept all browser downloads" (default aktif) di Settings; bila mati dipakai downloader sistem.
- **Deteksi & unduh video dari browser** (seperti video grabber 1DM):
  - Tiap tab memantau semua request (`shouldInterceptRequest`, hanya mengintip — respons tidak diganti) dan DOM halaman (`<video>`, `<audio>`, `<source>`, meta `og:video`, iframe satu origin, `MutationObserver` + pemeriksaan berkala + event `play` lewat jembatan JavaScript `XdmBridge` yang hanya menerima string). Pola yang dikenali: `.mp4 .m4v .webm .mkv .mov .3gp .flv .mp3 .m4a .aac .ogg .wav .m3u8 .mpd`, serta `mime=video`, `/hls/`, `videoplayback`, dll. Gambar/JS/CSS/font dan piksel pelacak diabaikan; potongan `.ts`/`.m4s`/`.aac` suatu stream hanya dihitung, tidak ditampilkan satu per satu. URL `blob:` (MSE) dilewati — manifest-nya tetap terdeteksi dari jaringan.
  - Kandidat diperiksa di latar belakang (HEAD atau `Range: bytes=0-0` dengan Cookie/User-Agent/Referer/Origin tab) untuk memastikan tipe konten dan ukuran; yang bukan media dibuang. URL di-dedupe (query dipertahankan karena biasanya berisi token). Daftar direset saat pindah halaman.
  - Tombol mengambang "unduh video" dengan lencana jumlah (berdenyut saat ada temuan baru) → sheet berisi tipe (MP4/WEBM/HLS/DASH/Audio), kualitas, ukuran (diperkirakan dari bandwidth × durasi untuk HLS, diberi "~"), dan item yang sedang diputar di paling atas. Master playlist HLS menampilkan tiap varian (resolusi/bitrate) dan trek audio terpisah; varian tanpa audio bawaan diberi label "video only". Memilih item membuka sheet "Add download" yang sudah terisi (nama = judul halaman + ekstensi, header Cookie/UA/Referer/Origin). Tekan lama pada `<video>` juga menawarkan "Download video with XDM".
  - **Unduhan HLS** (VOD): parser M3U8 (master + media, `BYTERANGE`, `MAP`, `KEY` AES-128, banyak key, `ENDLIST`), segmen diunduh paralel (pakai pengaturan "connections"), retry/backoff, progres segmen + kecepatan/ETA, dekripsi AES-128 (IV dari tag atau nomor media-sequence), hasil digabung: fMP4 (`EXT-X-MAP`) → `.mp4`, MPEG-TS → `.ts`, lalu disimpan ke `Download/XDM/`. Segmen yang selesai disimpan satu file per segmen di folder sementara, jadi pause/resume dan restart aplikasi melanjutkan dari segmen terakhir (bila playlist masih sama). Baris daftar unduhan menampilkan chip "HLS" dan "12 / 20 segments".
- **Konversi HLS `.ts` → `.mp4`** (Settings → "Convert HLS to MP4", default aktif): setelah semua segmen digabung, stream MPEG-TS disalin **tanpa re-encode** ke MP4 sungguhan (H.264/H.265 + AAC, juga MP3; hanya audio → `.m4a`) sehingga langsung bisa diputar di Galeri / Google Photos / pemutar apa pun dan bisa di-seek (moov di depan, tabel sampel lengkap). Memakai `androidx.media3` `TsExtractor` (baca sampel) + `Mp4Muxer` (tulis), tanpa ffmpeg.
  - Timestamp: PTS pertama dinormalisasi ke 0, wraparound 33-bit ditangani ekstraktor, dan lompatan jam di tengah file (`EXT-X-DISCONTINUITY`, iklan sisipan, jam yang di-reset) disambung jadi satu timeline monoton tanpa celah (`TsTimeline`, diuji tersendiri); audio dan video berbagi "epoch" yang sama supaya tetap sinkron.
  - Status di daftar/notifikasi: "Converting to MP4… NN%" (progres menurut byte yang dibaca). Pause/Cancel dihormati: cancel membuang semuanya; resume mengulang konversi dari `.ts` gabungan yang masih ada (tanpa mengunduh ulang). Memakai file sementara; setelah berhasil `.ts` dihapus. Ekstensi nama berubah ke `.mp4` (tabrakan nama tetap diberi `(1)`), MIME `video/mp4`.
  - Gagal (kodek tidak didukung, data rusak, ruang kurang) **tidak** menggagalkan unduhan: `.ts` disimpan seperti sebelumnya dan item diberi catatan "Saved as .ts (conversion failed: …)". Ruang kosong ±1x ukuran `.ts` diperiksa sebelum mulai.
- **Pemblokir iklan berbasis filter list** di browser bawaan (Settings → "Block ads", default aktif) — mesin filter murni Kotlin/JVM (`adblock/filters/`) untuk subset sintaks EasyList / AdGuard / uBlock Origin:
  - **Aturan jaringan**: `||host^`, `||host/path`, anchor `|http…` dan `…|`, wildcard `*`, pemisah `^`, substring biasa, `/regex/` (dikompilasi saat pertama dipakai; regex tidak valid dilewati), pengecualian `@@`. Opsi: `script, image, stylesheet, xmlhttprequest/xhr, subdocument/frame, media, font, object, ping, websocket, other, document/doc, popup` (+ negasi `~type`), `third-party/3p`, `~third-party/1p/first-party`, `domain=` (daftar `|`, negasi `~`, entitas `example.*`), `match-case`, `important`, `all`, `badfilter`, serta `@@…$document / $elemhide / $generichide / $specifichide` (pengecualian tingkat halaman). `redirect=` diperlakukan sebagai blokir untuk tipe aturan itu. Aturan dengan opsi yang mengubah makna (`csp`, `removeparam`, `replace`, `header`, `permissions`, `cookie`, `jsonprune`, `redirect-rule`, `denyallow`, `to`, `method`, …) dilewati tanpa error. Prioritas: blokir `important` > pengecualian > blokir.
  - **Pencocok cepat**: aturan host polos (`||host^`, juga `$third-party`) + hosts file + AdGuard DNS masuk ke himpunan domain ringkas (`CompactDomainSet`, satu byte array + tabel hash, jalan ke domain induk tanpa alokasi). Aturan lain diindeks per *token* literal paling langka (seperti uBO/adblock-rust); aturan tanpa token tetapi dengan `domain=` diindeks per situs. Pihak ketiga ditentukan lewat eTLD+1 dengan **Public Suffix List** yang dibundel (`a.example.co.uk` vs `b.example.co.uk` = pihak pertama; `alice.github.io` vs `bob.github.io` = pihak ketiga). Diukur di JVM: ±0,02–0,07 ms per request (10 ribu URL).
  - **Tipe request**: `shouldInterceptRequest` tidak memberi tahu tipe, jadi disimpulkan dari main frame → `document`, header `Sec-Fetch-Dest`, `X-Requested-With: XMLHttpRequest`, `Accept` (`text/css`, `image/`, `text/html` di sub-frame), lalu ekstensi (`.js/.mjs`, `.css`, gambar, font, `.m3u8/.ts/.mp4`…). Tanpa petunjuk → `unknown`: hanya aturan tanpa opsi tipe yang berlaku.
  - **Pop-up**: Settings → "Block pop-ups" = Off / Ads only / **All** (default). Setiap jendela yang dibuka halaman diberi WebView sungguhan yang belum ditampilkan; URL pertamanya dinilai dulu. Pop-up iklan (`$popup`, `$document`, aturan host, atau dibuka dari iframe iklan pihak ketiga) dibuang diam-diam dan dihitung; di mode "All" jendela lain juga ditahan dengan snackbar **"Pop-up blocked — Open"**; "Ads only" mengizinkan jendela dari sentuhan. "Open in new tab" dari tekan-lama tidak pernah diblokir.
  - **Redirect/click-jacking**: navigasi halaman utama yang bukan dari address bar (link, skrip, redirect server) ke tujuan yang cocok dengan aturan host/`$document` dibatalkan dan dihitung ("redirect blocked"); bila dipicu sentuhan pengguna, snackbar menawarkan "Open anyway".
  - **Kosmetik**: disuntikkan sedini mungkin (`addDocumentStartJavaScript` bila didukung, kalau tidak di `onPageStarted` + `onPageCommitVisible` + `onPageFinished`), di setiap frame: (a) selektor khusus situs (`contoh.com##…`, entitas `example.*`, negasi `~`) + selektor generik sederhana tanpa class/id (dibatasi 1500) dalam satu `<style>` (`display:none !important`, satu aturan per selektor); (b) generik gaya uBO: skrip mengumpulkan class & id di DOM (awal + `MutationObserver` yang di-throttle), mengirimnya lewat jembatan `XdmAds` (hanya string, ukuran dibatasi), dan menerima selektor generik yang memakai class/id itu. Pengecualian `#@#`, `$elemhide/$generichide`, dan allowlist per situs dihormati. Selektor prosedural (`:has-text()`, `:upward()`, `:xpath()`, `:style()`, …) dan scriptlet `+js()` tidak didukung.
  - **Daftar** (semua aktif secara default, bisa dimatikan per daftar di Settings): EasyList, AdGuard Base (tanpa EasyList), AdGuard DNS filter (hanya aturan domain utuh), StevenBlack hosts, dan daftar bawaan XDM `app/src/main/assets/xdm-extra.txt` (untuk aturan yang terlewat oleh daftar lain; di repo ini hanya berisi header). Aturan tambahan pribadi bisa ditambahkan saat build lewat `privateRulesFile` (lihat "Konfigurasi build privat"); aturan itu digabung ke daftar ini. Snapshot dibundel terkompresi di `app/src/main/assets/filters/` (±3 MB total, tanpa komentar/scriptlet) dan dibuat ulang dengan `./gradlew :app:updateFilterLists` (mengunduh; atau `-PfilterListsDir=/dir`); StevenBlack tetap lewat `./gradlew :app:updateBlocklist`. Catatan: packager APK menyimpan aset `*.gz` dalam bentuk sudah didekompresi dengan nama tanpa `.gz` (mis. `assets/filters/easylist.txt`), jadi aplikasi mencoba kedua nama dan pembacanya menerima gzip maupun teks biasa.
  - **Muat & update**: daftar dimuat di thread latar; selama belum siap semua request lolos. Hasil kompilasi disimpan sebagai cache biner (`filters.bin`, ±5,6 MB) sehingga start berikutnya hanya memuat indeks (±0,15–0,35 s di JVM, vs ±2–5 s mem-parse semua teks); cache dibuat ulang otomatis bila daftar, versi aplikasi, atau pilihan daftar berubah. Settings → "Update filter lists" mengunduh semua daftar yang aktif (hormati "Wi-Fi only"), memvalidasi, menyimpan via file sementara + pindah atomik, lalu menukar mesin aktif secara atomik; jumlah aturan dan tanggal tiap daftar ditampilkan. Satu daftar gagal tidak menghentikan yang lain.
  - **Video grabber**: pemblokiran dijalankan **sebelum** pendeteksi video (request maupun URL media yang dilaporkan DOM), jadi HLS iklan (mis. dari widget live-cam) tidak pernah muncul di daftar "unduh video". Halaman utama tidak diblokir di `shouldInterceptRequest`, dan situs yang sedang dibuka tidak memblokir sumber dayanya sendiri lewat aturan host.
  - **Kontrol**: ikon perisai di address bar menampilkan total yang diblokir pada halaman (request + pop-up + redirect); ketuk → sheet berisi rincian per kategori dan "Allow ads on this site" (allowlist per host, cocok juga untuk subdomain, disimpan di DataStore; halaman dimuat ulang). Item yang sama ada di menu browser. Settings → "Allowed sites" untuk menghapus entri. Unduhan yang dimulai pengguna (DownloadListener, "Download with XDM", sheet video) tidak melewati pemblokir sama sekali.
- **Unduh YouTube** (v0.6.0) memakai [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (versinya satu baris di `gradle/libs.versions.toml`: `newpipeExtractor`; kalau YouTube berubah dan unduhan gagal, naikkan tag itu). Tanpa yt-dlp/Python.
  - **Pintu masuk**: Share dari aplikasi YouTube atau aplikasi lain (`watch`, `youtu.be`, `shorts`, `m.youtube.com`, `music.youtube.com`, `embed`, `live`, replay live, `playlist`), tempel di "Add download" (link YouTube langsung dialihkan), tawaran clipboard, dan browser bawaan: di halaman video/Shorts/playlist tombol unduh membuka sheet YouTube. `m.youtube.com` adalah SPA, jadi alamat dibaca dari WebView saat tombol ditekan.
  - **Sheet format**: thumbnail, judul, channel, durasi, lalu pilihan. *Video (MP4)*: satu baris per resolusi, tertinggi dulu, dengan perkiraan ukuran — stream DASH video-saja (H.264/`avc1` diutamakan; AV1 hanya untuk resolusi di atas 1080p yang tidak punya H.264) + audio M4A/AAC terbaik, digabung jadi satu MP4. *Cepat*: stream progresif 360p yang sudah satu berkas. *Audio saja*: M4A (AAC), tanpa transkode. Centang "Remember my choice" untuk menjadikannya bawaan; **Settings → Default YouTube quality**: Ask / Best / 1080p / 720p / 480p / Audio (selain "Ask", link langsung ditambahkan tanpa sheet; resolusi yang tidak ada diganti yang terdekat di bawahnya).
  - **Unduhan**: tiap stream memakai mesin multi-segmen yang sama, dengan **segmen dibatasi ±10 MB** (googlevideo memperlambat range besar) dan paling banyak "connections" segmen sekaligus; video lalu audio, **satu baris** di daftar dengan progres gabungan ("Downloading video/audio", "Merging video and audio… NN%"). Pause/resume, antrian, notifikasi, dan foreground service sama seperti unduhan biasa.
  - **URL kedaluwarsa**: yang disimpan di baris unduhan hanya id video + itag, bukan URL. Tiap kali unduhan mulai (juga setelah pause lama atau ganti jaringan) video dicari ulang; bila server menjawab 403/410 di tengah jalan, URL baru diminta dan unduhan **dilanjutkan dari byte yang sudah ada di disk** (maks. 3 pembaruan beruntun tanpa kemajuan, lalu gagal dengan pesan jelas).
  - **Penggabungan**: `Mp4DashMuxer` (media3 `FragmentedMp4Extractor` → `Mp4Muxer`, tanpa re-encode); sampel video dan audio disisipkan berurutan waktu. Hasil MP4 biasa (bukan fragmen), bagian sementara dihapus setelah berhasil, lalu disimpan ke `Download/XDM/` sebagai `Judul (720p).mp4` / `Judul.m4a`. Judul dibersihkan untuk sistem berkas. Gabungan yang sudah jadi tapi belum tersimpan tidak diulang setelah aplikasi dimatikan.
  - **Playlist**: link playlist menampilkan jumlah video dan "Download all" dengan satu pilihan kualitas; tiap video menjadi item sendiri (stream dipilih saat item mulai). **Maksimal 200 video**; jika lebih, sheet menyebutkannya. Link video yang berada di playlist menawarkan "download the whole playlist". Channel tidak didukung.
  - **Pesan kesalahan** dalam bahasa biasa: dibatasi usia / perlu masuk akun, privat, dihapus, khusus member, berbayar, siaran langsung (tidak didukung), diblokir wilayah, "YouTube asked to confirm you're not a bot" (`ReCaptchaException`/`SignInConfirmNotBotException`), jaringan, dan "YouTube changed something; XDM needs an update." untuk kegagalan parsing. Apa pun yang dilempar library (termasuk Rhino/`NoClassDefFoundError`) ditangkap — tidak ada crash.
  - **Privasi crash report**: id video 11 karakter setelah `v=`, `youtu.be/`, `shorts/`, `embed/`, `live/`, `vi/`, id playlist (`list=`), host `googlevideo.com`, dan **pesan** exception extractor/Rhino/nanojson/jsoup (bisa memuat judul atau nama channel) dibuang dari stack trace; nama kelas dan frame tetap ada.
  - **Tes di perangkat (CI)**: build debug punya `YoutubeTestActivity` (hanya di source set `debug`, diekspor untuk `am start`) yang mengunduh dan menggabungkan stream terkecil Big Buck Bunny (`aqz-KE-bpKQ`, Creative Commons) lalu mencatat satu baris `YTTEST PASS <w>x<h> <durasi>s tracks=2` / `YTTEST BLOCKED <alasan>` / `YTTEST FAIL <alasan>`. `.github/scripts/smoke.sh` menjalankannya (batas 180 detik), menulis baris itu ke `result.txt` beserta screenshot; BLOCKED (cek bot/login dari IP runner) tidak menggagalkan job, FAIL menggagalkan.
- UI Compose M3: tema terang/gelap + dynamic color, filter All / Active / Completed / Failed, halaman detail (visualisasi segmen), pengaturan (koneksi, paralel, Wi-Fi only, tema, User-Agent).
- **Hapus massal**: menu ⋮ di layar Downloads → "Delete all (tab: N)" menghapus semua item pada tab yang sedang dipilih; tekan lama sebuah baris untuk mode pilih (pilih semua, bagikan, hapus). Dialog konfirmasi menampilkan jumlah + ukuran di disk dengan opsi "Also delete files from storage" (default aktif) — file benar-benar dihapus, tanpa undo. Unduhan aktif dibatalkan dulu lalu file sementara/segmen HLS dibersihkan; file yang sudah hilang cukup dibuang dari daftar. File MediaStore milik instalasi lama (Android 11+) dihapus lewat satu dialog izin sistem; di Android 10 file seperti itu dilaporkan "could not be deleted".

## Instal APK

1. Ambil `app-debug.apk` dari artifact CI (`xdm-debug-apk`) atau dari halaman *Releases*.
2. Di HP, izinkan "Install unknown apps" untuk aplikasi yang membuka APK, lalu buka file-nya.
3. Android 13+: izinkan notifikasi saat diminta.

> APK dari build biasa (termasuk CI) ditandatangani dengan kunci debug Android bawaan, jadi tanda tangannya berbeda antar mesin dan APK dari mesin lain tidak bisa meng-update yang sudah terpasang. Untuk tanda tangan tetap, pakai keystore sendiri lewat `private.properties` (lihat "Konfigurasi build privat"). Ini bukan setup rilis produksi.

## Build

Butuh JDK 17 dan Android SDK (platform 35, build-tools 35.0.0). Buat `local.properties`:

```
sdk.dir=/path/ke/android-sdk
```

```
./gradlew testDebugUnitTest assembleDebug
```

APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

### Konfigurasi build privat

Semuanya opsional; build tanpa ini tetap jalan. Isi `private.properties` di root repo (sudah di-`.gitignore`, begitu juga folder `private/`), atau berikan lewat `-Pxdm.<kunci>=…`. Kalau dua-duanya ada, `-P` menang.

| Kunci | Fungsi |
|---|---|
| `crashRelayUrl` | URL relay untuk crash report. Kosong = crash report tidak tersedia di build ini: tidak ada yang ditulis atau dikirim, dan Settings hanya menampilkan "Crash reports are not set up in this build". |
| `crashRelayKey` | Nilai header `X-App-Key` untuk relay itu. |
| `signingStoreFile` | Keystore untuk menandatangani build debug dan release (path relatif ke root repo, atau absolut). Kosong = kunci debug Android bawaan. |
| `signingStorePassword` | Password keystore. |
| `signingKeyAlias` | Alias kunci. |
| `signingKeyPassword` | Password kunci. |
| `privateRulesFile` | File aturan filter (sintaks EasyList) yang disalin ke aset `xdm-private.txt` dan digabung ke daftar `xdm-extra`. Kalau asetnya tidak ada, tidak terjadi apa-apa. |

## Struktur

- `engine/` — inti unduhan murni Kotlin/JVM (tanpa API Android): probe, pembagi segmen, engine multi-koneksi, parser M3U8 (`M3u8.kt`), engine HLS (`HlsEngine.kt`), utilitas nama file. Diuji dengan `MockWebServer`.
- `media/` — deteksi video murni Kotlin/JVM: `MediaClassifier` (klasifikasi URL), `MediaCollector` (daftar per tab: dedupe, pengelompokan segmen, item yang diputar), `MediaProbe` (HEAD/Range + analisis playlist), `HlsOptions` (pilihan kualitas/ukuran).
- `adblock/` — pemblokir iklan murni Kotlin/JVM: `HostsParser` (hosts file / format ringkas), `HostNames` (jalan ke domain induk), `AdBlocker` (saklar, allowlist, mode pop-up, keputusan BLOCK/SNIFF/PASS, CSS kosmetik), `PopupPolicy` (keputusan pop-up murni), `FilterListRepository` (muat snapshot/cache, update, tukar atomik).
- `adblock/filters/` — mesin filter: `FilterParser`, `NetworkRule` + `Glob` (pencocokan pola), `RuleIndex` (indeks token), `CompactDomainSet`, `CosmeticIndex`, `FilterEngine` + `FilterEngineBuilder`, `EngineCodec` (cache biner), `PublicSuffixList`, `RequestTypeInference`, `FilterLists` (daftar & format simpan).
- `remux/` — konversi MPEG-TS → MP4: `TsMp4Remuxer` (media3 `TsExtractor` + `Mp4Muxer`), `TsTimeline` (timeline monoton, murni Kotlin), `TsTrailer` (memaksa frame video terakhir keluar), `Mp4Esds` (patch object type MP3). Fixture TS kecil ada di `app/src/test/resources/remux/` (dibuat dengan `scripts/gen-remux-fixtures.sh`); tes jalan di Robolectric karena media3 memakai kelas framework.
- `youtube/` — YouTube: `YoutubeUrls` (parsing link, murni), `FormatSelector` + `YtPlan` (pemilihan format, nama berkas, rencana yang disimpan; murni), `YoutubeErrors` (pesan), `NewPipeResolver` + `NewPipeDownloader` (NewPipeExtractor di atas OkHttp), `YoutubeHeaders`. `core/YoutubeJob` menjalankan satu unduhan (cari → unduh per stream → gabung); `remux/Mp4DashMuxer` menggabungkan. Fixture DASH mini di `app/src/test/resources/youtube/` (`scripts/gen-youtube-fixtures.sh`).
- `core/` — `DownloadManager` (antrian, pause/resume, Wi-Fi only), penyimpanan file, monitor jaringan.
- `data/` — Room (download + segmen), DataStore (pengaturan).
- `service/` — foreground service dan notifikasi.
- `browser/` — state browser (`BrowserViewModel`: tab, WebView, incognito, intercept unduhan, deteksi video, pemblokir iklan), `MediaBridge` (jembatan JS + skrip yang disuntikkan), `AdScript` (skrip kosmetik + jembatan `XdmAds`), `ExternalLinks` (intent eksternal).
- `ui/` — Compose: daftar, detail, pengaturan, sheet tambah unduhan, layar browser, `ShareReceiverActivity`.

Skema Room diekspor ke `app/schemas/` (v1–v4) dan ikut di-commit; `MigrationTest` menjalankan `MIGRATION_1_2`, `MIGRATION_2_3` (kolom `kind`, `segmentsDone`, `segmentsTotal`), dan `MIGRATION_3_4` (kolom `extra` untuk rencana YouTube) pada database berisi data lalu membandingkannya dengan skema yang diekspor — termasuk rantai penuh v1 → v4. Tidak ada migrasi destruktif.

## Lisensi

XDM dirilis di bawah **GNU GPL v3.0 atau lebih baru** (lihat [LICENSE](LICENSE)), karena memakai NewPipeExtractor yang juga GPL.

## Kredit dan lisensi pihak ketiga

Library utama yang dipakai, semuanya lewat Maven/JitPack tanpa diubah:

| Library | Sumber | Lisensi |
|---|---|---|
| NewPipeExtractor | https://github.com/TeamNewPipe/NewPipeExtractor | GPL-3.0-or-later |
| OkHttp / Okio | https://github.com/square/okhttp | Apache-2.0 |
| AndroidX: Jetpack Compose, Material 3, Room, DataStore, WebKit, Lifecycle | https://developer.android.com/jetpack/androidx | Apache-2.0 |
| AndroidX Media3 (extractor, muxer) | https://github.com/androidx/media | Apache-2.0 |
| Kotlin, kotlinx.coroutines | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| desugar_jdk_libs | https://github.com/google/desugar_jdk_libs | GPL-2.0 with Classpath Exception |

Untuk tes: JUnit 4 (EPL-1.0), Robolectric (MIT), MockWebServer (Apache-2.0), sqlite-jdbc (Apache-2.0). Video uji YouTube adalah *Big Buck Bunny* (© Blender Foundation, CC BY 3.0).


**NewPipeExtractor** (YouTube) berlisensi **GPL-3.0-atau-lebih-baru** dan dipakai sebagai library tanpa perubahan; dependensinya: nanojson, jsoup (MIT), protobuf-javalite (BSD-3), Rhino (MPL-2.0). Akibatnya XDM secara keseluruhan harus diteruskan dengan ketentuan GPL-3.0 bila dibagikan ke orang lain. Catatannya ada di Settings → "About & licenses". Memakai YouTube lewat aplikasi pihak ketiga melanggar ketentuan layanan YouTube; pakai untuk keperluan pribadi dan atas tanggung jawab sendiri.

Daftar filter yang dibundel adalah karya terpisah dengan lisensinya sendiri. Isinya hanya dikurangi (komentar dan jenis aturan yang tidak dipakai XDM dibuang), tidak diubah. Semua teks lisensi ada di `app/src/main/assets/licenses/` dan bisa dibaca di aplikasi: Settings → "About & licenses".

| Daftar | Sumber | Lisensi | Snapshot |
|---|---|---|---|
| EasyList | https://easylist.to/easylist/easylist.txt | GPLv3 / CC BY-SA 3.0 (dual) | Last modified 09 Oct 2026 18:18 UTC |
| AdGuard Base (tanpa EasyList, varian uBO) | https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt | GPLv3 | 2026-10-09T17:05:11Z |
| AdGuard DNS filter (aturan domain saja) | https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt | GPLv3 | 2026-10-09T18:18:53Z |
| StevenBlack/hosts (unified) | https://github.com/StevenBlack/hosts | MIT (© Steven Black) | 2026-10-07 |
| Public Suffix List | https://publicsuffix.org/list/public_suffix_list.dat | MPL-2.0 | 2026-10-10 |

Ringkasan sumber, lisensi, dan tanggal ada di `licenses/filter-lists-NOTICE.txt`; teks lengkap di `GPL-3.0.txt`, `MPL-2.0.txt`, dan `stevenblack-hosts-LICENSE.txt`. Daftar bawaan `xdm-extra.txt` adalah bagian dari XDM.

## Keterbatasan (MVP)

- Belum ada pemecahan segmen dinamis; kecepatan bergantung pada pembagian awal.
- Memindahkan file ke `Download/XDM` menyalin file (butuh ruang kosong ±2x ukuran file sementara proses akhir).
- Tanpa auto-resume setelah reboot sebelum aplikasi dibuka sekali.
- **Video (batasan jujur):**
  - Stream **DRM** (Widevine/FairPlay/PlayReady: Netflix, Disney+, dll.) **tidak bisa** diunduh; HLS dengan `SAMPLE-AES` atau `KEYFORMAT` non-identity ditolak dengan pesan.
  - **YouTube** punya jalurnya sendiri (lihat bagian "Unduh YouTube" dan keterbatasannya di bawah); pemantauan jaringan umum tetap dimatikan di halaman YouTube karena request pemutarnya hanya potongan bertanda tangan dari `googlevideo.com`, bukan berkas.
  - **Live stream** (playlist tanpa `#EXT-X-ENDLIST`) ditampilkan sebagai "Live stream — not supported", tidak diunduh.
  - **DASH (`.mpd`)** hanya dideteksi dan ditampilkan sebagai "DASH not supported yet" (tidak ada unduhan, juga untuk `BaseURL` tunggal).
  - Pemutar **MSE/`blob:`** hanya bisa ditangani bila manifest/berkas aslinya terlihat di request jaringan.
  - HLS → MP4: konversi hanya untuk MPEG-TS dengan H.264/H.265 + AAC/MP3 (AAC saja → `.m4a`). Kodek lain (AC-3/E-AC-3, DTS, MPEG-2 video, MP2) **membuat konversi dibatalkan** dan `.ts` disimpan apa adanya dengan catatan; trek audio tambahan yang tidak didukung dilewati bila ada audio lain yang bisa disalin. ID3 timed metadata, subtitle, dan caption terpisah dibuang (caption CEA-608/708 di dalam SEI video ikut tersalin). MP3 ditulis sebagai `mp4a` dengan object type 0x6B (dipatch setelah muxer selesai karena media3 tidak mendukung MPEG audio). Frame B memakai `ctts` versi 1 (offset bertanda) — pemutar yang sangat lama bisa salah urut (tidak diuji). Perubahan resolusi/format di tengah stream ditolak. **Belum pernah dijalankan di perangkat**: hanya diuji di JVM (Robolectric) dan diverifikasi dengan ffprobe/ffmpeg. fMP4 (`EXT-X-MAP`) tidak perlu konversi. Dengan "Convert HLS to MP4" mati, MPEG-TS tetap `.ts`. Trek audio terpisah (`EXT-X-MEDIA TYPE=AUDIO`) tidak digabung ke video: varian dengan audio terpisah diberi label "video only", dan trek audio bisa diunduh sendiri.
  - HLS butuh ruang kosong ±3x ukuran stream sementara saat mengunduh (segmen + file gabungan); segmen dihapus begitu file gabungan jadi, lalu konversi MP4 butuh ±1x lagi (diperiksa dulu — kalau kurang, konversi dilewati dengan catatan) dan penyalinan ke `Download/XDM` ±1x. Resume HLS hanya jika playlist masih mencantumkan segmen yang sama (dibandingkan tanpa query string); kalau tidak, mulai dari awal. Key AES diambil ulang setiap kali unduhan dilanjutkan.
  - `Cookie` hanya dikirim ke host yang sama (atau sub/parent domain-nya) dengan playlist; segmen di CDN lain menerima Referer/Origin/User-Agent tanpa Cookie. Cookie/token disimpan saat unduhan ditambahkan — URL bertanda tangan yang kedaluwarsa membuat unduhan gagal (403) dan harus ditambahkan ulang dari browser.
  - Deteksi bersifat heuristik: ada kemungkinan item palsu (mis. iklan) atau video yang tidak terdeteksi; ukuran HLS hanyalah perkiraan.
- **YouTube (batasan jujur):**
  - Bergantung pada NewPipeExtractor, yang mengikuti perubahan YouTube: sewaktu-waktu unduhan bisa gagal dengan "YouTube changed something; XDM needs an update" sampai tag `newpipeExtractor` dinaikkan dan APK dibuat ulang. Tidak ada auto-update library.
  - **Tidak bisa masuk akun**: video dibatasi usia, khusus member, berbayar, dan privat tidak bisa diunduh. IP datacenter/VPN sering kena "confirm you're not a bot".
  - **Siaran langsung** (sedang berlangsung) dan premiere tidak didukung. Channel (daftar video) tidak didukung, hanya video dan playlist (maks. 200, sisanya dipotong dengan pemberitahuan). Playlist "mix" (`RD…`), "Watch later", dan "Liked" tidak bisa dibaca tanpa login.
  - **Video (MP4)**: hanya H.264 (sampai 1080p, termasuk 60 fps) dan AV1 dalam MP4 (1440p/2160p). **VP9 (WebM) dicoba dan tidak ditawarkan**: `Mp4Muxer` media3 menolaknya karena butuh record `vpcC` yang tidak ada di WebM YouTube. Opus/WebM tidak dipakai; audio selalu M4A (AAC), tanpa MP3. Video AV1 butuh pemutar/dekoder AV1 di HP (belum diuji di perangkat nyata).
  - Audio multi-bahasa: trek asli yang dipilih. Subtitle, chapter, dan thumbnail tidak disimpan.
  - Butuh ruang kosong ±2x ukuran total (bagian video + audio + hasil gabungan); diperiksa di awal. File hasil dengan lebih dari ±400 KB tabel sampel (video sangat panjang) memiliki atom `moov` di akhir berkas (sah, hanya tidak bisa di-stream sebelum selesai).
  - **Belum diuji**: perangkat nyata dan YouTube sungguhan dari lingkungan pengembangan ini. Yang teruji: logika (URL, pemilihan format, kesalahan, scrubbing), mesin dengan MockWebServer (URL kedaluwarsa, lanjut dari byte yang ada), penggabungan dengan fixture ffmpeg (hasilnya juga didekode ffmpeg tanpa galat), dan manager end-to-end dengan resolver palsu. Uji sebenarnya ke YouTube dilakukan oleh `YoutubeTestActivity` di emulator CI — hasilnya bisa BLOCKED bila YouTube menolak IP runner.
  - Ukuran APK debug bertambah ±3,6 MB (NewPipeExtractor + Rhino + jsoup + protobuf-lite + desugaring); waktu pencarian pertama setelah aplikasi dibuka lebih lama (±beberapa detik: skrip pemutar YouTube dijalankan di Rhino).
- **Pemblokir iklan (batasan jujur):**
  - **Scriptlet `##+js(...)`**, CSS injection `#$#`, aturan JS `#%#`, HTML filtering `$$`, selektor prosedural (`:has-text()`, `:upward()`, `:xpath()`, `:style()`, `:remove()`…), `redirect-rule=`, `removeparam`, `csp`, `header`, `$replace` dan sejenisnya **tidak didukung** (aturan itu dilewati). Situs yang hanya bisa dibersihkan dengan scriptlet (anti-adblock, iklan yang disuntikkan skrip first-party, iklan **YouTube**) tetap menampilkan iklan. Iklan di dalam stream video itu sendiri tidak bisa dihapus.
  - `redirect=` tidak mengganti resource dengan stub (noop.js dll.) melainkan memblokir — situs yang mengandalkan stub bisa rusak. `redirect-rule=` sengaja dilewati (bukan blokir): di uBO aturan ini tidak memblokir sendiri, dan banyak bentuknya (`*$script,redirect-rule=…,domain=…`) akan memblokir *semua* skrip situs bila diperlakukan sebagai blokir.
  - Tipe request hanya **disimpulkan** (WebView tidak menyebutkannya); request tanpa petunjuk dianggap `unknown` dan hanya kena aturan tanpa opsi tipe, jadi sebagian aturan bertipe (mis. `$xhr` tanpa ekstensi/header) tidak berlaku. `$third-party` dan `domain=` dinilai terhadap halaman utama (tab), bukan frame tempat request berasal.
  - Pop-up: pada mode "All" (default) juga link `target=_blank` yang diketuk ditahan dan harus dibuka lewat snackbar; jendela yang dibuka lewat "Open" tidak lagi punya `window.opener`. Jendela `about:blank` yang diisi skrip dinilai tanpa URL setelah 3 detik. Iframe asal pop-up hanya dikenali bila WebView menyertakan `Referer`.
  - Aturan host (hosts file, AdGuard DNS, `||host^`) juga memblokir navigasi dari link ke situs yang terdaftar; snackbar "Open anyway" muncul bila pengguna mengetuknya. Daftar mencakup pelacak/malware, jadi sesekali situs yang sah bisa rusak — gunakan "Allow ads on this site".
  - Respons kosong 204 untuk iframe/skrip yang diblokir bisa meninggalkan ruang kosong (elemen tidak di-*collapse*) atau memicu error di skrip situs yang bergantung pada skrip iklan.
  - Biaya: ±27 MB heap untuk semua daftar (diukur di JVM), cache biner ±5,6 MB di penyimpanan aplikasi, APK +±3,4 MB. Kompilasi pertama setelah instal/update daftar memakan beberapa detik di HP (diukur ±2–5 s di JVM desktop) — selama itu iklan belum diblokir.
  - Update daftar hanya manual, tanpa verifikasi tanda tangan selain HTTPS; tidak ada update otomatis berkala. Public Suffix List hanya diperbarui lewat versi aplikasi.
  - Belum pernah dijalankan di perangkat nyata: jalur WebView (intersepsi dengan tipe yang disimpulkan, pop-up tertahan, skrip kosmetik + jembatan `XdmAds`, snackbar, sheet perisai, layar Settings) hanya terkompilasi dan lolos lint; parser, pencocok, indeks kosmetik, kebijakan pop-up, cache biner, update daftar, dan fixture situs video (host rekaan, bentuk request seperti hasil rekaman Chrome) diuji lewat unit test JVM.
- Cookie/Referer/User-Agent dari browser disimpan saat unduhan ditambahkan dan tidak diperbarui: sesi yang kedaluwarsa membuat unduhan gagal (403) dan perlu ditambahkan ulang dari browser. Seperti browser, `Cookie` tidak diteruskan setelah redirect ke host lain (Referer dan User-Agent tetap).
- Browser: `blob:`/`data:` download tidak bisa di-intercept; tab tidak dipulihkan setelah proses dimatikan; tanpa video fullscreen, ikon situs, atau manajer izin lokasi/kamera (selalu ditolak).
- Incognito memakai profil WebView terpisah (`androidx.webkit` multi-profile) bila WebView-nya mendukung; kalau tidak, cache dimatikan dan cookie host yang dikunjungi dihapus saat tab incognito terakhir ditutup (cookie jar dibagi dengan tab biasa, jadi cookie host yang sama di tab biasa ikut terhapus; localStorage tidak dibersihkan).
- Mode gelap WebView mengikuti tema aktivitas (sistem), bukan pilihan tema di dalam aplikasi.
- Belum diuji di perangkat/emulator nyata (build headless); logika engine (termasuk HLS/AES-128), antrian, header, parser URL/M3U8, klasifikasi & pengumpulan media, dan migrasi Room diuji lewat unit test JVM. Kode WebView/Compose — termasuk skrip JS yang disuntikkan, jembatan JS, tombol/sheet video, dan menu tekan-lama pada `<video>` — hanya terkompilasi dan lolos lint; belum pernah dijalankan pada WebView sungguhan.
