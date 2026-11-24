# 📱 VREMOVER untuk Android

Versi Android-nya **bukan** nge-proses lagu di HP. Prosesnya jalan di **server VREMOVER** (Google Colab atau PC lu), dan app Android-nya jadi "remote" dengan tampilan violet ala Moises: pilih lagu, tunggu, lalu play dan download hasilnya.

```
HP (app VREMOVER)  ──upload lagu──▶  Server (Colab / PC)  ──▶  vokal + instrumental balik ke HP
```

## 1. Build APK lewat GitHub

1. Buat repo baru di GitHub, lalu push seluruh isi folder ini (branch `main`).
2. Buka tab **Actions → Build APK → Run workflow** (atau otomatis jalan tiap push ke folder `android/`).
3. Tunggu ±5 menit sampai hijau, lalu buka run-nya.
4. Download artifact **VREMOVER-apk**, extract, dan install `VREMOVER.apk` di HP.
   (Aktifin dulu *Install dari sumber tidak dikenal* di pengaturan HP.)

## 2. Jalanin server

### Opsi A: Google Colab (gratis, pakai GPU)

1. Buka `colab/VREMOVER_Server.ipynb` di [colab.research.google.com](https://colab.research.google.com) (File → Upload notebook).
2. Runtime → Change runtime type → **T4 GPU**.
3. Ganti `REPO` di cell 1 ke link repo GitHub lu, lalu jalanin cell 1 → 2 → 3.
4. Copy link `https://xxxx.trycloudflare.com` yang muncul.

### Opsi B: PC sendiri (satu WiFi dengan HP)

```bash
pip install -r server/requirements.txt   # + install FFmpeg
python server/app.py
```

Server nampilin alamat kayak `http://192.168.1.5:5000`. Pakai alamat itu di app.

## 3. Pakai di HP

1. Buka app **VREMOVER**, tempel link server, tekan **Simpan**.
2. Pilih lagu, pilih model, tekan **Mulai Proses**.
3. Setelah selesai, dengerin langsung atau tekan **Download** (masuk ke folder `Download/VREMOVER`).

Ganti server kapan aja lewat tulisan **Ganti server** di bagian bawah halaman.

## ⚠️ Catatan

- File project ini **belum pernah dicoba build/jalan langsung** oleh pembuatnya (nggak ada Android SDK dan internet buat pip di lingkungan pembuatan). Kalau ada error pas build atau jalanin server, kirim lognya biar diperbaiki.
- APK yang dihasilkan adalah **debug APK** (sudah bisa diinstall). Buat rilis ke Play Store perlu signing key sendiri.
- Link Colab itu publik (siapa pun yang tahu linknya bisa pakai), jadi jangan disebar. Colab gratis juga punya batas waktu sesi.
- Nama model di `server/app.py` mengikuti daftar `audio-separator`. Kalau ada yang nggak ketemu, cek daftar terbaru dengan `audio-separator -l` lalu ubah di list `MODELS`.
- Versi PC (`UVR.py`) tetap ada dan nggak berubah.
