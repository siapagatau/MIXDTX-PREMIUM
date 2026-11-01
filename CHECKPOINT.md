# CHECKPOINT — MIXJOS v1.2.1
Tujuan: APK DTX dijadikan APK mixer; pad -> kontrol mixer; sisanya tetap DTX supaya bisa dikembangkan bersama DTX.
v1.2 (keluhan: penataan belum rapi, layar utama dan floating beda): layar utama dan floating kini memanggil SATU `MixerGrid` (`MixerPanel.kt`).
Susunan lebar (landscape) dan tegak (floating sempit) dari data `MixGroups` yang sama; kartu datar & ringkas (judul 30dp, seluruh baris judul = area ketuk ON/OFF),
meter menyesuaikan bentuk tempat (mendatar/tegak), pegangan resize floating dipindah ke baris bawah sendiri, strip bawah dirapatkan (tombol 36dp).
Dihapus: daftar Switch + knob lepas di `FloatingBody.kt`; `MixerGrid/MixGroupCard/MixKnob` lama di `MainActivity.kt`.
v1.2.1: saklar bereaksi saat DIKETUK (onTap), bukan saat tersentuh; saklar skin selalu tap; floating ikut skin aktif (SkinHost, proporsi asli, tinggi jendela menyesuaikan sekali per skin);
teks tombol putih netral; keterangan judul bagian dihapus; skin contoh (assets/skins/default) dihapus - skin hanya dari repo (ID lama 'default' tanpa cache -> kembali ke klasik).
Dipertahankan: palet DTX (`DtxPalette.kt`), engine C++, `MixerBridge`, `SettingsStore/Bus`, `GlobalEq*`, Skin Engine.
Status: BELUM dikompilasi/diuji di HP (sandbox tanpa Android SDK). Next: 1) push -> Actions; bila gagal kirim log. 2) uji HP: layar utama landscape (knob cukup besar?),
floating ganti skin (real time), floating default 340x560 (tegak, gulir), floating dilebarkan >= 640dp (susunan lebar), ketuk judul kartu = ON/OFF, meter MIC/OUT, EQ audio, TEMA, SKIN.

v1.3.0: zoom floating saat skin aktif (`FloatingZoom.kt`, `FloatingBody.kt`, `SkinHost(inputLocked)`); tombol -/1x/+ di tengah baris bawah. Zoom = graphicsLayer saja (instan, tanpa render ulang; agak buram). Fitur JALUR/rekam layar dibatalkan & dihapus. BELUM diuji di HP.

v1.4.0: REVERB AUDIO (reverb + delay manual untuk suara app lain, kirim per band Sub/Low/Mid/High). Alur: MediaProjection -> AudioPlaybackCapture -> `SysReverb.cpp` (reuse `FreeverbStyle` + `EchoEffect`, crossover 90/400/4000 Hz, send per band) -> `AudioTrack` memutar EFEKNYA SAJA (wet-only; suara asli tetap dari app sumber).
File baru: `cpp/SysReverb.h/.cpp`, `SysReverbEngine.kt` (pengaturan + JNI), `SysReverbService.kt` (foreground service mediaProjection), `SysReverbDialog.kt` (panel knob). Diubah: `native-lib.cpp`, `CMakeLists.txt`, `AndroidManifest.xml` (izin FOREGROUND_SERVICE_MEDIA_PROJECTION + service), `MainActivity.kt` (tombol REVERB AUDIO di strip bawah + izin rekam layar).
Skala kalibrasi (diukur di PC): reverb wet penuh = +9 dB di atas masukan -> knob penuh dipetakan 0.28; echo mix penuh dipetakan 0.6.
Batas: Android 10+; app yang melarang capture (sebagian streaming/DRM) tidak kena efek; izin rekam layar diminta tiap dinyalakan. Uid app ini dikecualikan dari capture (mencegah umpan balik dari mic karaoke).
Status: C++ SUDAH dikompilasi + diuji di PC (tanpa NaN, OFF senyap). Kotlin BELUM dikompilasi, seluruh fitur BELUM diuji di HP. Next: push -> Actions; bila gagal kirim log. Uji HP: nyalakan, putar YouTube, cek ekor reverb, toggle ON/OFF, send per band, dan pastikan TIDAK ada suara melengking (umpan balik).

