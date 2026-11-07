# RidiExtractor

Aplikasi untuk mendekripsi buku dari Ridibooks dan mengemasnya kembali ke format `.epub`.

## Cara Penggunaan
Aplikasi ini tidak lagi memerlukan akses root langsung pada saat menjalankan dekripsi karena pengguna dapat mengunggah file yang diperlukan secara mandiri.

Unggah tiga file/data berikut pada aplikasi:
1. **File ZIP berisi folder `extracted`**
   - File zip dari isi folder buku (berisi struktur `OEBPS/Text`, `content.opf`, serta file cover di `extracted/OEBPS/Images/cover.jpg` atau `OEBPS/Images/cover.jpg`).
2. **`com.initialcoms.ridi_preferences.xml`**
   - File preferences Ridibooks yang memuat tag `<string name="uuid">` (Device ID / UUID).
3. **`{bookid}.dat`**
   - File data/kunci enkripsi buku spesifik dari Ridibooks.

Setelah semua file dipilih:
- Pratinjau judul, penulis, dan gambar sampul buku (cover) akan ditampilkan secara otomatis jika ditemukan di dalam ZIP.
- Tekan tombol **Dekripsi & Buat EPUB**.
- File hasil dekripsi akan disimpan dalam format `.epub` pada folder `Download/RidiExtractor/`.

## Acknowledgement
Thanks [@luoyily](https://github.com/luoyily) for providing help on Android reverse engineering.
