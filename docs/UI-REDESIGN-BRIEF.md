# Test Senaryo Stüdyosu — UI yeniden tasarım brifingi

Bu dosya bir tasarım aracına (Claude Design) doğrudan verilmek üzere yazıldı.
Ekran görüntüleriyle **birlikte** verilir; tek başına ekran görüntüsü yanıltır,
çünkü bu arayüzün zorluğu boş hâlinde değil dolu hâllerinde.

---

## 1. Ürün nedir

Yapı Kredi kurumsal internet bankacılığı için **AI destekli test otomasyonu**
arayüzü. Bir iş analisti şunu yapar:

1. Bir ekran (katalog) seçer, ne test edileceğini düz Türkçe yazar
2. Model, kapalı bir adım sözlüğünü kullanarak bir Cucumber/Gherkin senaryosu
   üretir
3. Analist senaryoyu okur, düzenler, doğrular
4. Çalıştırır; gerçek bir tarayıcıda koşan adımları canlı izler
5. Raporu/trace'i indirir

**Kullanıcı geliştirici değil.** Gherkin okur ve düzenler, kod yazmaz.

---

## 2. Mevcut yapı

```
┌─ Üst bar: marka · [Çalışma Alanı][Geçmiş] · executor durumu · tema ─┐

GÖRÜNÜM 1 — Çalışma Alanı (iki sütun)
┌───────────────────────────┬──────────────────────────────┐
│ Senaryo düzenleyici       │ Yan panel (4 sekme)          │
│  · satır numarası sütunu  │  · Adım Sözlüğü              │
│  · textarea               │  · Zaman Çizelgesi  ●canlı   │
│  · [rozet][Doğrula][Çalıştır] │  · AI Üret               │
│  · doğrulama hata listesi │  · Ajan                      │
└───────────────────────────┴──────────────────────────────┘

GÖRÜNÜM 2 — Geçmiş: filtre çipleri + 8 sütunlu tablo

Her yerde: sağ altta toast
```

---

## 3. Değiştirilemez kısıtlar

Bunlar tercih değil, ortam dayatması:

1. **CDN yok. Web font yok.** Bu kısım gerçekten ortam dayatması: kurumsal SSL
   denetimi **tarayıcının** dışarı çıkışını kesiyor, bu yüzden her varlık Spring
   Boot jar'ından servis edilmek zorunda (`src/main/resources/static/`) ve
   **Tailwind CDN ile Google Fonts kullanılamaz.**

   **npm için aynı şey doğru değil — ölçüldü.** 2026-10-07'de VDI'da:
   `node v20.20.2`, `npm 10.8.2`, ve `npm install react@18.3.1 react-dom@18.3.1`
   → `added 5 packages ... in 7s`, `gmproxy.kfs.local:8080` proxy'si üzerinden.
   Ayrıntı: `E2E-CERCEVE-DEGERLENDIRME.md` bölüm 6. Dolayısıyla "React/npm
   kullanılamaz" bir **ortam gerçeği değil, bir proje tercihidir** — gerekçesi
   `SUNUM-BAGLAMI.md`'de yazılı: analistin ihtiyacı olan ekran küçük, bağımlılık
   taşımaya değmez. Tercihi değiştirmek ayrı bir karardır ve bu brief'in konusu
   değildir.

   **Bu brief kapsamında tercih aynen geçerli: build adımı yok, düz CSS +
   vanilya JavaScript.** Tasarımı buna göre yapın.
2. **Üç dosya:** `index.html`, `app.css`, `app.js`. Başka dosya yok.
   (Bu da 1. maddedeki tercihin sonucu, ortamın değil.)
3. **Tema:** `<html data-theme="dark|light">`, `localStorage`'da saklanıyor.
   Her renk `:root`'ta bir jeton ve koyu tema için yeniden tanımlı.
4. **Arayüz dili Türkçe**, ve etiketler uzun: "Orijinal Fatura Numarası",
   "Taksit Periyot Birimi".
5. **Editör** = `textarea` + ayrı satır-numarası sütunu, ikisi **senkron
   kaydırmak zorunda**. Sözdizimi renklendirme bugün yok.
6. Backend sözleşmesi (20 REST ucu) **değişmiyor**; tasarım yalnızca sunum
   katmanı.

### Bugünkü jetonlar

```css
--brand: #004587;  --brand-hover: #003a72;  --brand-tint: #eaf1f8;
--bg: #f4f6f8;     --surface: #ffffff;      --border: #dee3e9;
--text: #17212c;   --text-muted: #5a6773;   --text-faint: #8b96a2;
--ok: #0b7a3e / #e6f4ec      --fail: #c0272d / #fdecec
--run: #0b63b8 / #e8f1fb     --skip: #8b96a2 / #f1f3f5
--warn: #9a6200 / #fdf3e2
--radius: 8px;  --mono: sistem mono yığını;  --sans: system-ui
```

Marka lacivertini koru; gerisi serbest.

---

## 4. Tasarlanması gereken 13 durum

Boş ekran tasarlamak kolay; bu arayüz dolu hâlinde zorlanıyor.

| # | Durum | Neden |
|---|---|---|
| 1 | Doğrulama **HATALI** — kırmızı satır numaraları, "N hata" rozeti, hata kartları, "Bunu mu demek istediniz" öneri düğmeleri | En yoğun ekran, asıl sınav |
| 2 | Doğrulama **GEÇERLİ** — yeşil "20 adım geçerli" | 1'in karşıtı |
| 3 | Zaman çizelgesi **RUNNING** — bir adım ●, kalanlar ○, canlı nokta | |
| 4 | Zaman çizelgesi **FAILED** — kırmızı ✕, uzun hata metni, alt barda Rapor/Günlük/Trace | Taşma davranışı |
| 5 | Ajan — dolu YAML çıktısı + `problems` listesi | Kenar çubuğuna sığmayan panel |
| 6 | Ajan — kaydetme satırı açık (`ngc` / `akis-adi` / `.yaml`) | Sadece katalog üretilince belirir |
| 7 | AI Üret — form dolu, "2 model çağrısı", geçerli/geçersiz rozeti | |
| 8 | Adım Sözlüğü — 20 adım, 6 grup başlığı | Varsayılan açılış |
| 9 | Geçmiş — 4-5 koşu, farklı durumlar, filtre çipleri | 8 sütun |
| 10 | Boş durumlar — geçmiş boş / "Henüz çalıştırılmadı" / "Eşleşen adım yok" | Üçü ayrı |
| 11 | Koyu tema — en az 1 ve 3 | |
| 12 | Toast — başarı ve hata | |
| 13 | Dar pencere ~1280px | Tablo + iki panel sığmıyor |

---

## 5. Gerçek içerik — lorem ipsum kullanma

### Senaryo (düzenleyicide görünen)

```gherkin
Feature: Online DBS Fatura Oluştur
  FORD DBS ODEMELERI kurumu için taksitli bir DBS faturası oluşturma.

  Scenario: Bayi seçilip fatura oluşturulur
    Given login with cif "DI00002018" and userid "1335"
    And navigate to "NAKİT YÖNETİMİ" "Online DBS İşlemleri"
    And select row "FORD DBS ODEMELERI"
    And click "Fatura Oluştur"

    # Bayi Kodu ÖNCE gelmeli: uygulama buna tepki olarak Bayi Ünvanı'nı
    # dolduruyor ve Döviz Cinsi'nin seçeneklerini yüklüyor.
    And select "93002" from "Bayi Kodu"
    Then verify the "Bayi Ünvanı" field equals "TEST / TEST UNVANI"

    And enter "Başlangıç Vadesi" "19/09/2026"
    And enter amount "Toplam Tutar" "200,00"
    And select "Ay" from "Taksit Periyot Birimi"
    And click "Devam"
    And click "Onay"
```

Dikkat: senaryolar **yorum satırı içeriyor** ve yorumlar uzun. Boş satırlarla
gruplanıyor. Tasarım bunu desteklemeli.

### Doğrulama hataları (JSON)

```json
{
  "valid": false,
  "stepCount": 21,
  "errors": [
    { "line": 9, "step": "verify the \"Bayi Ünvanı\" field equals \"Ford Otomobil Sanayi ve Ticaret A.Ş.\"",
      "message": "\"Bayi Ünvanı\" bu senaryoda doldurulmadi ve beklenen deger \"Ford Otomobil Sanayi ve Ticaret A.Ş.\" istekte de yok — uygulamanin turettigi bir degeri tahmin etmeyin. Ya alani doldurun, ya bu dogrulamayi silin, ya da beklenen degeri isteğe yazin.",
      "suggestions": [] },
    { "line": 14, "step": "Tutar bilgisini giriniz",
      "message": "Tanımsız adım",
      "suggestions": ["enter amount {string} {string}", "enter {string} {string}"] }
  ]
}
```

**Mesajlar uzun** (ilki 200+ karakter) ve `suggestions` tıklanabilir
düğmelerdir — basınca o satırı düzeltirler.

### Koşu durumu (JSON)

```json
{
  "status": "RUNNING",
  "currentStepIndex": 5,
  "steps": [
    { "keyword": "Given", "text": "login with cif \"DI00002018\" and userid \"1335\"",
      "status": "PASSED", "durationMs": 3800 },
    { "keyword": "And", "text": "verify the \"Bayi Ünvanı\" field equals \"Ford Otomobil Sanayi ve Ticaret A.Ş.\"",
      "status": "FAILED", "durationMs": 15100, "error": "Locator expected to have value" },
    { "keyword": "And", "text": "enter \"Başlangıç Vadesi\" \"29/09/2026\"",
      "status": "SKIPPED", "durationMs": null }
  ]
}
```

Durumlar: `PENDING` `RUNNING` `PASSED` `FAILED` `SKIPPED` `UNDEFINED`.
Adım metinleri uzun; süreler `3.8 sn` / `485 ms` / `15.1 sn` biçiminde.

### Ajan `problems` listesi — gerçek bir çıktı

> • success_message var ama hicbir alan verifiable degil. Sonuc ekranina
> ulasilmis, demek ki onay ekranindan gecilmis, ama note_confirmation
> cagrilmamis. Bu katalogtan uretilen senaryo formu doldurur, Onay'a basar ve
> yalnizca banner'i dogrular: sessizce bos kalan bir alan yine yesil gecer.
>
> • Onay ekraninda su etiketler vardi ama hicbir alan olarak kaydedilmemis:
> Kurum FORD DBS ODEMELERI, Bayi Kodu 93002, Bayi Ünvanı TEST / TEST UNVANI,
> Başlangıç Vadesi 29/09/2026, Fatura No 6092104, Orijinal Fatura Numarası
> 955231, Döviz Cinsi TL, Taksit Adedi 3, Toplam Tutar 222,00 TL, Orijinal
> Fatura Tutarı 22,00 TL, Taksit Periyot Birimi Ay, Taksit Periyodu 2, Fatura
> Dönemi 2, Fatura Tarihi 30/09/2026, Fatura Tipi Normal. Ekranda bu alanlar
> var ve katalog onlari bilmiyor.

İki madde, biri 15 etiketlik bir paragraf. Bugün düz madde işaretli satırlar
hâlinde; **tasarımı yok.**

### Adım Sözlüğü paneli — gerçek 20 adım, 6 grupta

Panel bu listeyi `GET /api/v1/vocabulary`'den alıyor ve başlıklara ayırıyor.
Her satır tıklanabilir bir düğme: basınca düzenleyiciye eklenir.
`{string}` / `{int}` yer tutucuları ayrı renkte gösteriliyor.

```
Giriş
  login with cif {string} and userid {string}
  login to ngi with cif {string}
Navigasyon
  go to {string}
  navigate to {string} {string}
  click {string}
Veri Girişi
  enter {string} {string}
  enter amount {string} {string}
Seçim
  select {string} from {string}
  select account {string} from {string}
  select account at index {int} from {string}
  select row {string}
  select row at index {int}
Doğrulama
  verify the {string} field equals {string}
  verify the {string} has amount {string}
  verify {string} text is displayed
Diğer
  check {string}
  wait {string} seconds
```

### Ajan çıktı kutusu — gerçek YAML

Bu metin düzenlenebilir bir `textarea` içinde duruyor; analist düzeltip
kaydediyor.

```yaml
app: NGC
flow: Fatura Oluştur

navigation:
  main_menu: "NAKİT YÖNETİMİ"
  sub_menu: "Online DBS İşlemleri"
  entry_row: "FORD DBS ODEMELERI"
  entry_button: "Fatura Oluştur"

fields:
  - label: "Bayi Kodu"
    type: dropdown
    verifiable: true
    options:
      - "93001"
      - "93002"
  - label: "Bayi Ünvanı"
    type: text
    readonly: true
    verifiable: true
  # emin degil: tarih kutusu mu metin kutusu mu ayirt edemedim
  - label: "Başlangıç Vadesi"
    type: date
    verifiable: true
  - label: "Toplam Tutar"
    type: amount
    verifiable: true

buttons:
  - "Devam"
  - "Onay"

success_message: "Fatura Oluşturma işleminiz başarıyla gerçekleşmiştir."
```

### Başarı mesajı — bir yere sığması gerekiyor

> Ödeme talebiniz karşı tarafa iletilmiştir. Tarafınıza yapılacak ödemenin
> detaylarını Banko İşlemleri'nden görüntüleyebilirsiniz.

---

## 6. Çözülmesi istenen problemler

"Güzelleştir" yerine bunları problem olarak ver:

1. **Ajan paneli kenar çubuğuna sığmıyor.** 7 input + 10 satırlık talimat
   kutusu + 14 satırlık çıktı kutusu + kaydetme satırı, dar bir sütunda. En
   çok yeniden düşünülmesi gereken yer.
2. **Ajan koşusu dakikalar sürüyor, ilerleme göstergesi yok** — tek bir
   "birkaç dakika sürebilir" satırı var. İptal düğmesi de yok.
3. **`problems` listesinin tasarımı yok** (yukarıdaki örneğe bak).
4. **Geçmiş tablosu 8 sütun**: Durum · Senaryo · Executor · Başlangıç · Süre ·
   Adım · Koşu · Dosyalar. Dar ekranda taşıyor.
5. **Beş yan sekme tek sütunda yarışıyor** (Adım Sözlüğü · Zaman Çizelgesi ·
   AI Üret · **Çoklu Üret** · Ajan). Koşu başlayınca "Zaman Çizelgesi" öne
   çıkıyor ama diğer dördü kayboluyor; analist sık sık sözlük ile çizelge
   arasında gidip geliyor.
6. **Doğrulama hata listesi düzenleyicinin altında**, uzun senaryoda hem hata
   hem ilgili satır aynı anda görünmüyor.
7. **Altıncı bir panel gelebilir** ("Ekranı Keşfet" — deterministik DOM
   taraması). Backend hazır, arayüzde bağlı değil. Tasarım 5 yerine 6 panele
   uyabilmeli.
8. **"Çoklu Üret" düzenleyicinin yerini alıyor.** Üretim bitince editör
   gizleniyor ve yerine bir kart listesi geliyor (`#multiList`); bir karta
   tıklanınca editör geri dönüyor ve üstünde bir konum barı açılıyor
   (`#multiBar`: `← Tümü` + `Senaryo 2 / 5` + varyasyon çipleri). Yani çalışma
   alanının **üç** durumu var, bir değil — tek senaryo / batch listesi / batch
   taslağı. Tasarımın bu geçişi taşıması gerekiyor.
   Ayrıntı: [`COKLU-SENARYO-AKISI.md` §8](COKLU-SENARYO-AKISI.md#8-faz-4--yanıt-ve-ekran)

---

## 7. Teslim beklentisi

- `index.html` + `app.css` + `app.js` olarak uygulanabilir olmalı
- Dış bağımlılık **sıfır**
- Açık ve koyu tema
- Mevcut element id'leri korunursa iyi olur; `app.js` bunlara bağlı
  (`editor`, `gutter`, `validation`, `validationBadge`, `btnRun`, `timeline`,
  `suggestCatalog`, `suggestIntent`, `agentOutput`, `agentSaveRow`, …)
