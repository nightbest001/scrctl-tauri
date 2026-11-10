# Catatan rilis

Format: satu bagian per versi. Saat membuat tag `vX.Y.Z`, workflow memakai bagian `## vX.Y.Z` sebagai isi release.
Kalau bagian itu tidak ada, dipakai bagian `## Belum dirilis`. Ganti judul bagian itu dengan nomor tag sebelum rilis.

## Belum dirilis

## v0.8.5

### Diperbaiki
- Toggle akses jarak jauh (ngrok) mati sendiri tanpa alasan. Keluaran galat ngrok (stderr) sekarang dibaca, tampil di konsol dan di kotak galat, lengkap dengan kode keluar.
- Pesan jelas untuk galat sesi ngrok ganda (ERR_NGROK_108).

### Ditambahkan
- Kontrol PC dari HP: touchpad (gerak, klik, seret, gulir), keyboard (teks, Ctrl/Alt/Win/Esc, pintasan), volume dan tombol media, serta layar PC di app HP.
- Kontrol HP ke HP: satu HP jadi target (alamat dan kode), HP lain mengontrol lewat hotspot atau WiFi yang sama.
- Akses jarak jauh lewat ngrok dari PC sendiri, dengan deteksi ngrok dan tombol Pasang ngrok.
- Link terenkripsi (protokol v2): saling membuktikan kode, AES-256-GCM, anti-replay, kunci 30 detik setelah 5 kode salah.
- Tanda di title bar Orbit saat layar PC sedang dikirim.

### Diubah
- Kode Link baru 12 karakter (kode 8 karakter lama masih bisa dipakai). App HP lama (protokol v1) tidak kompatibel: perbarui APK.
- Tampilan app Windows dan Android dirapikan: warna, jarak, dan teks lebih konsisten.

### Diperbaiki
- Kolom pencarian di app Windows punya garis ganda.
- Build APK gagal karena tanda kutip di teks bantuan `MainActivity`.

### Dihapus
- Font Montserrat bawaan beserta teks lisensinya (memakai font sistem Segoe UI).
- Emoji di tombol dan label.
- Catatan "data Link belum dienkripsi" dari app dan README.
