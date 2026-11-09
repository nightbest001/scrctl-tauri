# SCRCTL (Tauri + Rust)

Control panel mirroring & kontrol layar Android lewat adb. Ini port dari versi Electron ke
**Tauri v2** — backend ditulis ulang di **Rust** (`src-tauri/src/main.rs`), UI lama dipakai lagi
lewat shim (`renderer/tauri-shim.js` menerjemahkan `window.api.*` ke `invoke/listen` Tauri).

## Build .exe lewat GitHub Actions (disarankan)

1. Push folder ini ke repo GitHub (branch `main`).
2. Tab **Actions → Build Windows** jalan otomatis (atau klik *Run workflow*).
3. Selesai → buka run-nya → **Artifacts → SCRCTL-windows**:
   - `SCRCTL-portable.zip` — ekstrak, jalankan `SCRCTL.exe` (adb sudah ikut di folder `platform-tools`)
   - `SCRCTL_..._x64-setup.exe` — installer
4. Mau rilis? Buat tag: `git tag v0.3.0 && git push --tags` → file otomatis masuk GitHub Releases.

Kalau step **Build** merah, buka lognya dan kirim error-nya — itu bagian `cargo` yang compile kode Rust.

## Build di Windows sendiri

Butuh: Node 18+, Rust (rustup), Microsoft C++ Build Tools, WebView2 (sudah ada di Win 10/11).

```powershell
# opsional: taruh adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll di src-tauri/resources/platform-tools
npm install
npm run dev      # jalan mode dev
npm run build    # hasil: src-tauri/target/release/scrctl.exe + installer di bundle/nsis
```

Kalau `platform-tools` kosong, app memakai `adb` dari PATH.

## SCRCTL Link (app Android pendamping)

App kecil di `android/` (Kotlin + Jetpack Compose) yang tersambung ke SCRCTL lewat WiFi,
tanpa kabel dan tanpa adb. Server-nya ada di `src-tauri/src/link.rs` (port TCP 5588).

Fitur:
- **Notifikasi HP → PC** (muncul di halaman Notifikasi). Butuh izin "Akses notifikasi" di HP.
- **Clipboard dua arah**. PC → HP otomatis. HP → PC lewat tombol "Kirim clipboard" atau menu Bagikan,
  karena Android 10+ melarang app background membaca clipboard.
- **Kirim file** HP ↔ PC. File dari HP masuk ke `Downloads\SCRCTL`, file dari PC masuk ke `Download/SCRCTL` di HP.
- **Baterai HP** live, plus kontrol cepat dari PC: senter, volume, mute, Jangan Ganggu, cari HP.

Cara pakai: buka menu **Link HP** di SCRCTL, install APK di HP, isi alamat dan kode yang tampil.

Keamanan: kode tidak dikirim lewat jaringan (handshake HMAC-SHA256), tapi isi data **belum dienkripsi**.
Pakai di WiFi yang kamu percaya. Windows bisa minta izin firewall saat pertama jalan.

### Build APK lewat GitHub Actions

Push ke GitHub, tab **Actions → Build Android APK** (atau *Run workflow*), lalu ambil
**Artifacts → SCRCTL-Link** (`SCRCTL-Link.apk`). Tag `v*` otomatis melampirkan APK ke Releases.
APK ditandatangani debug key, jadi bisa langsung di-install; Play Protect mungkin menanyakan konfirmasi.

### Fitur tambahan (terinspirasi NectarLink)

Semua lewat koneksi Link yang sama (protokol JSON, lihat `android/.../Features.kt` dan `src-tauri/src/link.rs`):

- **Kartu musik** di Beranda: musik di HP (sesi media Android, butuh Akses notifikasi) dan di PC (Windows SMTC,
  `src-tauri/src/pcmedia.rs`) — judul, artis, cover (HP), seek, play/pause/next/prev. Kalau dua-duanya ada, pilih PC/HP.
- **Wi-Fi / Bluetooth / Hotspot** di Kontrol HP: dijalankan lewat adb (`svc wifi|bluetooth`, `cmd connectivity start-tethering`),
  jadi butuh HP terhubung via adb (menu Setup). Status Wi-Fi/BT dibaca app Link; status hotspot hanya diketahui kalau Android mengizinkan.
- **Pesan** (SMS, baca + balas + nomor baru), **Telepon** (riwayat, telepon dari PC, jawab/tolak/akhiri, panggilan masuk),
  **Foto** (thumbnail + unduh file asli ke Downloads\SCRCTL), **Apps** (daftar app + buka di HP lewat adb), **Deck** (tombol pintas:
  media key PC, buka URL/app, perintah HP). Izin HP: app Link → Izin → "Pesan, kontak, telepon, foto".
- Belum ada: MMS, suara panggilan di PC, window app terpisah, enkripsi data Link.

## Struktur

- `src-tauri/src/main.rs` — semua backend: adb, client scrcpy (video/audio/control), UHID, clipboard, notifikasi HP, setelan, window.
- `src-tauri/resources/scrcpy-server.jar` — server resmi scrcpy v4.1 (Apache 2.0), di-embed ke exe.
- `renderer/` — UI (HTML/CSS/JS). `tauri-shim.js` = jembatan ke Rust.
- `src-tauri/src/link.rs` — server SCRCTL Link (HP ↔ PC).
- `android/` — app Android SCRCTL Link.
- `.github/workflows/build-windows.yml`, `build-apk.yml` — build otomatis.

## Beda dengan versi Electron

- Stream video/audio dikirim ke window mirror lewat `Channel` Tauri (byte mentah), decode tetap WebCodecs di WebView2.
- Notifikasi desktop pakai notifikasi native Windows.
- Drag & drop file ke window mirror ditangani di Rust (event window), bukan HTML5 drop.
- Data (daftar device, setelan) disimpan di `%APPDATA%\com.scrctl.app\`. Data lama Electron tidak otomatis pindah.
- Lisensi: scrcpy-server Apache 2.0 (lihat `LICENSE-scrcpy.txt`).
