# AI ile çalıştır — do_steps ve set_date

[← İçindekiler](README.md) · [← Sayfa ve bağlam](05-sayfa-ve-baglam.md) · [Sonuç, kayıt ve Gherkin →](07-sonuc-kayit-gherkin.md)

---

## 15. do_steps: birkaç adım tek turda

**Dosya:** `StepRunner.java` (`ToolCallback`, adı `do_steps`, `MAX_STEPS` = 8).

**Neden:** her araç çağrısı bir turdur ve her tur system prompt'u, araç
tanımlarını ve sayfayı yeniden öder. 12.11.2026'daki ölçümde dört adım için
altı tur gitti ve her turun üçte biri aynı sabit parçalardı.

**Neden referansla değil adla:** ilk `do_steps` (40a2a7f, geri alındı) her
adım için modelin **önceden** seçtiği ref'i alıyordu. Oysa İşlemler paneli
her satır tıklamasında yeniden çiziliyor (Fatura İptal Et: f3e747 → f3e776 →
f3e805). İptal ekranının "Geri"si de "Fatura İptal Et"e basılmadan sayfada
yok. Bu yüzden model öğeleri **sayfada yazdığı gibi adlandırır**. Kod her
adımı, o adım çalışırken **en son sayfada** bulur.

### 15.1 Şema

```json
{"steps": [
  {"action": "select", "name": "Firma Seçimi",  "value": "ASAŞ ALÜMİNYUM SAN."},
  {"action": "row",    "name": "Ödenecek"},
  {"action": "click",  "name": "Fatura İptal Et"},
  {"action": "click",  "name": "Hayır"}
]}
```

Esnek okuma: `steps` / `actions` / `items` anahtarları, çıplak dizi, string
olarak yazılmış dizi ya da tek nesne kabul edilir. Alan adları da esnektir:
`action|type|do`, `name|label|element|field|target|ref|key|text`,
`value|option|date|text`. 8'den fazla adım **kesilmez, reddedilir**. Yarısı
yapılmış bir toplu iş, hiç başlamamış olandan kötüdür, çünkü model hangi
yarının yapıldığını çıkarmak zorunda kalır.

### 15.2 Eylemler

| `action` | Ada göre ne aranır (en son tam sayfada) | Ne yapılır | Geri okuma |
|---|---|---|---|
| `click` | Rolü `button, link, tab, menuitem, checkbox, radio, option, treeitem, switch` ve **adı tam eşit** (büyük-küçük harf ve boşluk duyarsız) | `browser_click {target: ref}` | — |
| `type` | Etiketi ya da adı eşit `textbox / spinbutton / searchbox` | `browser_type {slowly:true}` | — |
| `date` | Aynı | `set_date` | Sonuç "Set to" ile başlamalı |
| `select` | Etiketi eşit, `aria-hidden` olmayan `combobox` | Gerçek `<select>` ise `browser_select_option`. Değilse: kutuya tık → seçeneği ara → yoksa `browser_press_sequentially(value)` → seçeneğe tık | Kutu artık `value`'yu gösteriyor mu |
| `row` | Hücresi metne **tam eşit** olan **ilk** `row` | Satıra tık (radyoya değil: radyo gizli) | Satırın radyosu `[checked]` mi |
| `press` | — | `browser_press_key {key}` (F5 kapıda reddedilir) | — |

### 15.3 Durma koşulları

```
her adım için:
   perform(adım)
     ├─ ad 0 öğeyle eşleşti       → "YAPILMADI: sayfada "X" adlı … yok"            ┐
     ├─ ad >1 öğeyle eşleşti      → "YAPILMADI: "X" 3 … ile eşleşti (button e1, …)" │
     ├─ alt çağrı reddedildi      → "REDDEDİLDİ: <kapının cümlesi>"                 ├─► DUR
     ├─ alt çağrı hata aldı       → "HATA: <ilk satır>"                             │
     └─ geri okuma tutmadı        → "YAPILMADI: seçenek tıklandı ama … göstermiyor" ┘
   ekran YENİ bir şey söylüyor mu? (önceki adımda olmayan dialog / alertdialog /
                                      alert, ya da "… giriniz." / "… seçiniz." satırı)
     └─ evet → "ekran yeni bir şey söylüyor: …" → DUR
```

### 15.4 Modelin gördüğü cevap

```
1. select "Firma Seçimi" = "ASAŞ ALÜMİNYUM SAN." → tamam
2. row "Ödenecek" → tamam (4 satır eşleşti, ilki: 955231 222,00 TL Ödenecek)
3. click "Fatura İptal Et" → YAPILMADI: sayfada "Fatura İptal Et" adlı tıklanabilir öğe yok

DURDU. Bundan sonraki adımlar YAPILMADI — aşağıdaki sayfaya bakıp devam et.

### Page
...  (en son sayfa; dış BudgetedTool bunu da özetler)
```

**Güvenlik:** her alt adım **sarmalanmış** araçlarla çağrılır. Onay engeli,
TIKLAMA listesi, F5, bütçe, takılma sayacı ve bant, `do_steps`'in içini de
modelin kendi çağrısı gibi görür. Bantta alt adımlar kendi araç adlarıyla
yer alır. `do_steps` kaydının kendisi `TapeRenderer` ve `ClaimCheck` için
sessizdir.

---

## 16. set_date

**Dosya:** `DateTool.java`. Model hangi kutuya hangi tarihin gireceğini
söyler, dört tarayıcı çağrısını kod yapar:

```
set_date(target, "25/09/2026")
  1. click target                                     takvimi aç
  2. select_option #ui-datepicker-year  ["2026"]      önce YIL
  3. select_option #ui-datepicker-month ["8"]         sonra AY (jQuery UI 0 tabanlı: Eylül = 8)
  4. click #ui-datepicker-div a:text-is("25")         en son GÜN (text-is: "2" ≠ "12")
  → "Set to 25/09/2026. … do not type into the box afterwards …"
```

Sıra önemlidir: yıl ya da ay seçmek gün ızgarasını yeniden çizer, önce
seçilen gün kaybolur. Bu dört çağrı **ham** araçlarla yapılır. Bütçeden
yemez ve modelin hamlesi sayılmaz, ama kendi `[uptest] set_date:`
satırlarını loglar.
