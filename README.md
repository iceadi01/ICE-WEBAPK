# Tools ICE v2.1

Versi ini dibangun ulang sebagai proyek Android Gradle normal.

## Perbaikan scanner galeri

- Tidak memakai `<input type="file">` dari WebView.
- Tidak memakai `BarcodeDetector` JavaScript.
- Galeri dibuka memakai `ActivityResultContracts.GetContent`.
- Foto dibaca native memakai ML Kit Barcode Scanning dengan model yang sudah dibundel di APK.
- Tidak memerlukan internet saat pemindaian setelah APK terpasang.

## Build melalui GitHub Actions

1. Upload seluruh isi folder ini ke root repository GitHub.
2. Buka tab **Actions**.
3. Pilih **Build Tools ICE APK**.
4. Tekan **Run workflow**.
5. Setelah selesai, unduh artifact **Tools-ICE-v2.1-APK**.

## Perbaikan build v2.1

- Menambahkan resource warna `ice_blue` yang sebelumnya hilang dan menyebabkan Android resource linking gagal.
- Workflow menampilkan error ringkas dan mengunggah log build jika proses gagal.
