# Tools ICE v2.3 User

Versi user dengan login website ICE Inject.

- Server default: https://accuracy.fwh.is
- Login: `/login-app.php`
- Pemeriksaan sesi: `/check-session.php`
- Username dan alamat server disimpan; password dan token sesi tidak disimpan permanen.
- Setiap proses aplikasi dimulai ulang, user wajib login kembali.
- Setelah login, tools offline tetap dapat digunakan selama aplikasi masih hidup.
- Respons server eksplisit nonaktif/expired akan mengunci aplikasi.
- Gangguan jaringan sementara tidak langsung mengunci sesi yang belum expired.
- Scanner galeri menggunakan ML Kit native.

Build melalui GitHub Actions: `.github/workflows/build-apk.yml`.
