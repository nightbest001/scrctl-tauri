# Orbit

Aplikasi desktop Windows untuk mirroring dan mengontrol HP Android dari PC, dengan app pendamping
(**Orbit Link**) untuk notifikasi, pesan, telepon, foto, kamera, dan transfer file lewat WiFi.

Mirroring layar memakai [scrcpy](https://github.com/Genymobile/scrcpy) lewat adb. Aplikasi dibangun dengan
Tauri v2: backend ditulis dengan Rust, antarmuka dengan HTML/CSS/JS.

## Fitur

**Mirroring dan kontrol (adb + scrcpy)**
- Mirror layar dengan audio, kontrol mouse dan keyboard, dan input HID (UHID).
- Sinkronisasi clipboard, serta drag and drop file ke jendela mirror.
- Mengelola beberapa perangkat, termasuk koneksi nirkabel.
- Kontrol cepat Wi-Fi, Bluetooth, dan hotspot lewat adb.

**Orbit Link (tanpa kabel dan tanpa adb)**
- Notifikasi HP tampil di PC.
- Clipboard dua arah dan kirim file HP ↔ PC.
- Status baterai, dan kontrol cepat: senter, volume, mute, Jangan Ganggu, cari HP.
- Pesan (baca, balas, kirim ke nomor baru) dan telepon (riwayat, panggil, jawab, tolak, akhiri).
- Galeri foto dengan unduhan file asli, dan pembuka daftar aplikasi HP.
- Kamera HP sebagai sumber video di PC.
- Kartu musik di Beranda untuk HP dan PC (Windows SMTC).
- Deck: tombol pintas untuk media key, URL, aplikasi, dan perintah HP.

**Kontrol PC dari HP (Orbit Link)**
- Touchpad: gerak, klik kiri/kanan/tengah, seret, dan gulir (dua jari atau jalur gulir).
- Keyboard: ketik teks langsung ke PC, tombol khusus (Esc, Tab, Enter, panah, Home, End), modifier Ctrl/Alt/Shift/Win, dan pintasan siap pakai.
- Volume dan tombol media.
- Layar PC di app HP: sentuh untuk klik, tahan lalu geser untuk seret. Ada pilihan kualitas dan mode layar penuh.
- Default mati. Nyalakan di Orbit: menu **Link HP**, bagian **Kontrol PC dari HP**. Saat layar PC dikirim, tanda muncul di title bar Orbit.

**Kontrol HP ke HP (Orbit Link)**
- Di HP target: buka **HP ini bisa dikontrol**, nyalakan layanan Aksesibilitas Orbit Link, tekan **Mulai** (Android meminta izin rekam layar), lalu catat alamat (`ip:5589`) dan kode 12 karakter.
- Di HP pengontrol: isi alamat dan kode itu di kolom koneksi Orbit Link, lalu buka **Kontrol PC**. Layar HP target tampil, ketuk untuk klik, geser untuk menggeser, tahan untuk tekan lama, dua jari untuk Kembali.
- Tombol Kembali, Beranda, Terbaru, Notifikasi, Pengaturan cepat, dan Kunci layar ada di tab Navigasi. Ketik, volume, dan media juga bisa.
- Syarat: kedua HP satu jaringan (mis. hotspot salah satunya), tanpa internet. Data dienkripsi dengan protokol yang sama seperti ke PC.
- Batasan: geseran dijalankan saat jari dilepas, hanya layar utama dalam orientasi saat mulai, dan layar tidak tampil jika izin rekam layar ditolak.

**Akses jarak jauh (ngrok)**
- Orbit menjalankan ngrok dari PC kamu sendiri (`ngrok tcp 5588`) dan menampilkan alamat publiknya. Isi alamat itu di app Orbit Link seperti alamat WiFi.
- Orbit mendeteksi apakah ngrok sudah terpasang. Kalau belum, tombol **Pasang ngrok** memasangnya otomatis (winget, atau unduhan resmi ke folder data Orbit). Perlu authtoken dari dashboard.ngrok.com. Tunnel TCP bisa meminta akun terverifikasi. Alternatif tanpa itu: Tailscale.
- ngrok hanya meneruskan data yang sudah terenkripsi. Pakai kualitas layar Hemat untuk koneksi jarak jauh.

Belum didukung: MMS, suara panggilan di PC, dan layar PC selain monitor utama.

## Unduh

Build terbaru ada di halaman [Releases](https://github.com/nightbest001/scrctl-tauri/releases).

| Berkas | Isi |
| --- | --- |
| `Orbit-portable.zip` | Versi portabel. Ekstrak lalu jalankan `Orbit.exe`. adb sudah disertakan. |
| `Orbit_<versi>_x64-setup.exe` | Installer Windows. |
| `Orbit-Link-v<versi>.apk` | App Android pendamping. |

## Memulai

1. Jalankan Orbit di PC.
2. Untuk mirroring, aktifkan USB debugging di HP lalu ikuti menu **Setup**.
3. Untuk Orbit Link, pasang APK di HP, buka menu **Link HP** di Orbit, lalu isi alamat dan kode yang tampil di app HP.
4. Di app Link, buka bagian **Izin** dan aktifkan akses yang dibutuhkan. Setiap fitur sensitif (kamera, pesan, telepon, foto) punya toggle sendiri yang bisa dimatikan kapan saja.

Persyaratan: Windows 10/11 dengan WebView2 untuk PC, Android 8.0 (API 26) atau lebih baru untuk app Link.

> **Keamanan.** Link memakai protokol v2: HP dan PC saling membuktikan kode lewat HMAC-SHA256, kunci sesi
> diturunkan dengan PBKDF2 (100.000 putaran) dan seluruh data sesudah handshake dienkripsi AES-256-GCM
> (satu kunci per arah, penghitung nonce, anti-replay). Kode baru panjangnya 12 karakter, dan 5 kode salah
> berturut-turut mengunci port selama 30 detik. Kekuatan sistem tetap bergantung pada kode: jangan bagikan,
> dan buat kode baru jika bocor. Authtoken ngrok disimpan polos di folder data Orbit. HP lama (v1)
> tidak kompatibel, perbarui APK. Windows dapat meminta izin firewall saat pertama kali dijalankan (port TCP 5588).

Android 13+ dapat memblokir dialog izin untuk app yang dipasang dari luar Play Store. Jika itu terjadi,
buka Info app → ⋮ → **Izinkan setelan terbatas**, lalu ulangi. APK ditandatangani dengan debug key,
sehingga Play Protect mungkin meminta konfirmasi saat pemasangan.

## Build dari source

Prasyarat: Node.js 18+, Rust (rustup), Microsoft C++ Build Tools, WebView2.

```powershell
# opsional: taruh adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll di src-tauri/resources/platform-tools
npm install
npm run dev      # mode pengembangan
npm run build    # src-tauri/target/release/scrctl.exe + installer di bundle/nsis
```

Jika `platform-tools` kosong, aplikasi memakai `adb` dari `PATH`.

Untuk app Android, buka folder `android/` dengan Android Studio, atau jalankan
`gradle assembleRelease` (JDK 17).

### CI

| Workflow | Hasil |
| --- | --- |
| `.github/workflows/build-windows.yml` | Installer dan zip portabel |
| `.github/workflows/build-apk.yml` | `Orbit-Link-v<versi>.apk` |

Keduanya berjalan pada push ke `main` dan pada tag `v*`. Tag melampirkan hasil build ke Releases dan
menetapkan versi aplikasi:

```bash
git tag v0.6.7
git push --tags
```

Tanpa tag, versi diambil dari `src-tauri/tauri.conf.json`. App Link memeriksa pembaruan dari Releases.

## Struktur proyek

```
src-tauri/
  src/main.rs      Backend: adb, klien scrcpy (video/audio/kontrol), UHID, clipboard, setelan, window
  src/link.rs      Server Orbit Link (HP ↔ PC)
  src/pcmedia.rs   Media PC lewat Windows SMTC
  src/phone.rs     Kontrol HP lewat adb (Wi-Fi, Bluetooth, hotspot, buka app) dan Deck
  resources/       scrcpy-server.jar dan platform-tools
renderer/          Antarmuka (HTML/CSS/JS); tauri-shim.js menjembatani window.api ke Rust
android/           App Android Orbit Link (Kotlin, Jetpack Compose)
.github/workflows/ Build otomatis
```

Protokol Link berbasis JSON lewat TCP. Penanganan pesan di sisi HP ada di
`android/app/src/main/kotlin/app/scrctl/link/Features.kt`.

## Catatan teknis

- Video dan audio dikirim ke jendela mirror lewat `Channel` Tauri sebagai byte mentah, lalu didekode
  dengan WebCodecs di WebView2.
- Notifikasi desktop memakai notifikasi native Windows.
- Data (daftar perangkat dan setelan) disimpan di `%APPDATA%\com.scrctl.app\`.

## Lisensi dan atribusi

`scrcpy-server.jar` adalah server resmi scrcpy v4.1 milik Genymobile, berlisensi Apache 2.0
(lihat `src-tauri/resources/LICENSE-scrcpy.txt`).
