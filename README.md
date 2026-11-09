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

## Struktur

- `src-tauri/src/main.rs` — semua backend: adb, client scrcpy (video/audio/control), UHID, clipboard, notifikasi HP, setelan, window.
- `src-tauri/resources/scrcpy-server.jar` — server resmi scrcpy v4.1 (Apache 2.0), di-embed ke exe.
- `renderer/` — UI (HTML/CSS/JS). `tauri-shim.js` = jembatan ke Rust.
- `.github/workflows/build-windows.yml` — build otomatis.

## Beda dengan versi Electron

- Stream video/audio dikirim ke window mirror lewat `Channel` Tauri (byte mentah), decode tetap WebCodecs di WebView2.
- Notifikasi desktop pakai notifikasi native Windows.
- Drag & drop file ke window mirror ditangani di Rust (event window), bukan HTML5 drop.
- Data (daftar device, setelan) disimpan di `%APPDATA%\com.scrctl.app\`. Data lama Electron tidak otomatis pindah.
- Lisensi: scrcpy-server Apache 2.0 (lihat `LICENSE-scrcpy.txt`).
