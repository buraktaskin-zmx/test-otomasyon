# Açık Sorular — Maaş Ödemeleri / Alıcı Listesi

> Bu dosya modele **gitmez**. UI'da uyarı şeridi olarak gösterilir.
> Buradaki konularda beklenti uydurulmamalı; kural yazılmadan senaryo üretilmez.

**Öncelik:** 🔴 senaryo yazılamaz · 🟡 senaryo yazılır, beklenti eksik · 🟢 detay

---

## 🔴 Engelleyici

### S-18 — Sentetik TCKN ile kayıt eklenebiliyor mu?
Algoritma kontrolünü (R-KE-002) geçen ama gerçek bir kişiye ait olmayan
sentetik bir TCKN ile kayıt eklenebiliyor mu? Sistem kişi varlığını
doğruluyor mu?

- **Nasıl cevaplanır:** Checksum'u doğru bir TCKN üretip bir kez dene
- **Geçerse:** Test verisi sorunu yok, TCKN runtime'da üretilir
- **Geçmezse:** Test ortamından gerçek bir TCKN temin edilmeli
- **Kilitlediği:** Kayıt ekleme, güncelleme, kayıt silme, dolu tablo
  gerektiren tüm senaryolar — yaklaşık yarısı
- **Not:** Hesap No'da validasyon olmaması (R-KE-004), kişi/hesap
  eşleşmesinin de kontrol edilmediğini düşündürüyor

---

## 🟡 Davranışı belirsiz

### S-19 — Aynı TCKN aynı listeye iki kez eklenebilir mi?
S-18 cevaplandıktan sonra test edilebilir. Mükerrer kayıt engelleniyorsa
hata mesajı nedir?

### S-06 — Döviz cinsi uyumu nasıl kontrol ediliyor?
R-AD-013 "kayıtlar listenin döviz cinsine uygun olmalı" diyor, ancak
kayıt formunda döviz alanı yok. Kontrol Hesap No üzerinden mi yapılıyor?
Hesap No'da hiç validasyon olmadığına göre (R-KE-004) bu kural pratikte
işliyor mu?

### S-11 — Yetkisiz kullanıcı davranışı
Yetkisiz kullanıcıda Güncelle / Sil / Yeni Kayıt Ekle butonları gizli mi,
pasif mi, yoksa tıklanınca hata mı veriyor? Test için hangi rol kullanılacak?
Yetkili bir test kullanıcısı mevcut, yetkisiz kullanıcı henüz yok.

### S-20 — Boş liste ödemeye yüklenebilir mi?
0 kayıtlı bir alıcı listesi, Alıcı Listesi'nden Yükleme ekranında
seçilebiliyor mu? Devam edilebiliyor mu?

### S-08 — Son kayıt silinirse liste ne olur?
Listedeki tek kayıt silindiğinde liste 0 kayıtlı olarak kalıyor mu,
yoksa liste de siliniyor mu?

### S-12 — Yüklenmiş listede sonradan güncelleme
Ödemeye yüklenmiş bir listede sonradan kayıt güncellenirse, önceden
oluşturulmuş ödeme etkileniyor mu?

---

## 🟢 Detay

### S-17 — Tablo içinde bul hangi kolonlarda arıyor?
Maskeli TCKN arama kapsamında mı? Sayfalama var mı, varsa arama tüm
sayfalarda mı çalışıyor?

### S-21 — Tutar üst sınırı var mı?
Kayıt tutarı için maksimum bir değer tanımlı mı? Negatif tutar girilebiliyor mu?

### S-22 — E1 varsayılan tarih aralığı
Gözlenen varsayılan aralık 19/10/2016 – 19/01/2027 (yaklaşık 10 yıl geri,
3 ay ileri). Bu kasıtlı mı, yoksa hatalı bir varsayılan mı?

### S-23 — Hızlı Tarih Seçim varsayılanı
Confluence ekran görüntüsünde "Bu Hafta", canlı ekranda "Seçiniz" görünüyor.
Hangisi doğru? Seçenekler neler?

### S-24 — Tutar gösteriminde YTL
E1 tablosunda tutarlar "30,00 YTL" olarak görünüyor, döviz cinsi ise "TL".
Gösterim hatası mı, bilinçli mi?

### S-25 — Tarih formatı tutarsızlığı
Oluşturma Tarihi `2026-03-31` formatında, filtre alanları `19/10/2026`
formatında. Tutarsızlık kasıtlı mı?

---

## Cevaplanmış sorular (arşiv)

| # | Soru | Cevap | Nereye gitti |
|---|------|-------|--------------|
| S-03 | Liste silme onayı nasıl? | Ayrı onay ekranı | R-LS-001..005 |
| S-05 | Güncellemede Hesap No düzenlenebilir mi? | Evet | R-AD-007 |
| S-07 | Seçimsiz Detay/Sil ne olur? | İlerlenemez | R-AL-012 |
| S-13 | Hata/başarı mesajları | Büyük ölçüde toplandı | `rules.yaml` messages |
| S-16 | Sorgu otomatik mi? | Hayır, Listele butonu var | R-AL-011 |
| — | Liste adı 101+ karakter | Girilmiyor, 100'de kalıyor | R-YL-004 |
| — | Liste adı alt sınır | Yok, tek harf olabilir | R-YL-005 |
| — | Hesap No validasyonu | Yok | R-KE-004 |
| — | Aynı isimde liste | UI'da ele alınmamış | `known-issues.md` B-01 |
