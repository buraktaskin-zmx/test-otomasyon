# Ekran Haritası — Maaş Ödemeleri / Alıcı Listesi

## Ürünün amacı

Kurumsal bir firma her ay yüzlerce çalışanına maaş öder. Her ödeme için
alıcının kimlik bilgisi, hesabı ve tutarı gerekir; bu bilgiler aydan aya
neredeyse hiç değişmez. **Alıcı listesi**, firmanın bir kez kurup her ay
tekrar kullandığı bir şablondur.

İki ayrı iş var ve karıştırılmamalıdır:

```
HAZIRLIK                              KULLANIM
────────────────────────────          ────────────────────────────
Alıcı Listesi İşlemleri               Alıcı Listesi'nden Yükleme
  liste sorgula / oluştur / sil         listeyi seç → ödemeye aktar
  kayıt ekle / güncelle / sil           → Onay ödeme talimatını oluşturur
```

---

## Ekran ağacı

```
Ödemeler > Maaş Ödemeleri
│
├── [E1] Alıcı Listesi İşlemleri
│     Filtre: Firma | Döviz Cinsi | Hızlı Tarih Seçim
│             └─ Detaylı Arama → Başlangıç Tarihi | Bitiş Tarihi | Listele
│     Tablo:  ( ) Grup No | Alıcı Listesi Adı | Oluşturma Tarihi
│                | Toplam Tutar | Kayıt Sayısı
│     Boş:    "Kayıt bulunamadı."
│     İşlemler: Detay | Sil | Yeni Liste Oluştur
│        │
│        ├── Detay ───────────► [E2]   (seçim zorunlu)
│        ├── Sil ─────────────► [E4]   (seçim zorunlu)
│        └── Yeni Liste ──────► [E5]   (seçimden bağımsız)
│
├── [E5] Yeni Liste Oluştur          — 2 adım
│     Adım 1 Bilgi Girişi: Firma | Döviz Cinsi | Alıcı Listesi Adı (0/100)
│                          → Devam
│     Adım 2 Onay: Firma Adı/Kodu | Firma Şubesi | Döviz Cinsi
│                  | Alıcı Listesi Adı
│                  → Geri | Onay
│
├── [E4] Alıcı Listesi Sil           — tek adım onay
│     Onay: Firma Adı/Kodu | Firma Şubesi | Alıcı Listesi Adı
│           | Toplam Tutar | Toplam Kayıt Sayısı
│           → Geri | Onay
│     Sonuç: başarı şeridi
│
├── [E2] Alıcı Listesi Detay
│     Özet:  Firma Adı/Kodu | Döviz Cinsi | Firma Şubesi
│            | Alıcı Listesi Adı | Grup No | Toplam Kayıt Sayısı | Toplam Tutar
│     Tablo: ( ) TCKN | Hesap No | Ad Soyad | Tutar     + "Tablo içinde bul"
│     İşlemler: Güncelle | Sil | Yeni Kayıt Ekle
│     Geri ──► E1
│        │
│        ├── Güncelle ───► [P1] Alıcı Listesi Kayıt Güncelle
│        │                   TCKN (maskeli, salt okunur)
│        │                   Hesap No | Tutar (TL , kuruş)
│        │                   Vazgeç | Kaydet
│        ├── Sil ─────────► onay → başarı popup'ı (Tamam)   ⚠ B-02
│        └── Yeni Kayıt ──► [P2] Alıcı Listesi Kayıt Ekle
│                            TCKN | Hesap No | Tutar (TL , kuruş)
│                            Vazgeç | Kaydet
│
└── [E3] Alıcı Listesi'nden Yükleme  — 2 adım  (bu sürümde kapsam dışı)
      Adım 1 Liste seçimi + Detay popup
      Adım 2 İşlem özeti → Onay
```

---

## Ekranlar arası veri ilişkisi

```
E1 satırı (liste)  ──1:N──►  E2 kayıtları (alıcılar)
   Kayıt Sayısı = E2'deki satır sayısı
   Toplam Tutar = E2'deki Tutar toplamı

E2'de ekle / güncelle / sil  ──►  E1 ve E3'teki Kayıt Sayısı + Toplam Tutar
E1'de liste sil              ──►  liste E3'te seçilemez
E5'te liste oluştur          ──►  liste E1'de ve E3'te görünür
E4 onay özeti                ──►  E1'deki satırla aynı değerler
```

---

## Paylaşılan bileşenler

Aynı yapı birden fazla ekranda tekrarlanıyor; YAML'da ortak component
olarak tanımlanmalı:

| Bileşen | Nerede |
|---|---|
| Firma filtresi (Firma + Döviz + Hızlı Tarih + Detaylı Arama) | E1, E3 |
| İşlem Bilgileri özet tablosu | E4, E5 Adım 2, E3 Adım 2 |
| İşlemler paneli (sağ sütun) | E1, E2, E3 |
| Sıralanabilir tablo + radio seçim | E1, E2, E3 |
| Tutar girişi (TL , kuruş) | P1, P2 |
| Sihirbaz adım göstergesi | E4, E5, E3 |

---

## Gezinme sırası (veri bağımlılığı)

YAML katalogları üretilirken bu sıra izlenmelidir. Her adım bir sonrakinin
ön koşulunu yaratır:

```
1. [E1] boş hali            ön koşul yok
2. [E5] Yeni Liste Oluştur  ön koşul yok   ◄── veriyi BU üretir
3. [E1] dolu hali           artık ≥1 liste var
4. [E2] Detay (0 kayıt)
5. [P2] Yeni Kayıt Ekle     ⚠ GEÇERLİ TCKN GEREKİR (S-18)
6. [E2] dolu hali
7. [P1] Güncelle popup      Vazgeç ile çıkılır, veri korunur
8. Kayıt silme onayı        Vazgeç ile çıkılır, veri korunur
9. [E4] Liste silme onayı   Geri ile çıkılır, liste korunur
10. [E3] Adım 1 + Detay popup
11. [E3] Adım 2 özet
```

**5. adım zincirin tek kırılma noktasıdır.** Geçerli bir TCKN olmadan
6–8 arası gezilemez ve bu ekranların katalogları üretilemez.
