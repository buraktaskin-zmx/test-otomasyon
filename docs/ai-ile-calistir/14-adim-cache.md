# AI ile çalıştır — Adım cache'i ve adım içi Beklenen

[← İçindekiler](README.md) · [← Örnek koşu](13-ornek-kosu-2628c4fd.md)

---

## 29. Adım cache'i: ikinci koşuda ekran yeniden gezilmez

### 29.1 Ne yapar

Bir senaryo "AI ile çalıştır" ile bir kez **GEÇTİ** olduktan sonra, o
yürüyüşün adımları `AI_RUN_CACHE` tablosuna yazılır. Aynı senaryo bir daha
çalıştırıldığında:

1. Giriş ve menü bugünkü gibi kodla yapılır (`AiRunPrelude`).
2. Cache'teki adımlar **kodla, model olmadan** sırayla uygulanır
   (`CachedWalk` → `StepRunner`).
3. Adımlar bitince **tek bir AI turu** yalnızca sonucu değerlendirir:
   adım içi beklentilerin sayfaları ve son sayfa + Beklenen → `report_result`.

Bugün bir koşu ~25 tur ve ~100 bin token harcıyor. Cache'li bir koşu bir tur
ve birkaç bin token harcar. **Karar hiçbir zaman eski koşudan alınmaz;** her
koşuda yeniden verilir.

```
AI ile çalıştır ─► AiRunPrelude (giriş + menü, kodla)
                    │
                    ├─ AiRunCache.lookup: senaryo id + metnin SHA-256'sı
                    │
          MISS ─────┤──── HIT
           │        │      ▼
           │        │    CachedWalk: adımlar tek tek StepRunner ile (model YOK)
           │        │      ├─ hepsi tamam ─► TEK AI TURU (yalnız report_result aracı)
           │        │      └─ k. adım tutmadı / tırnaklı beklenen görülmedi
           │        │             ─► ajan o sayfadan devralır
           ▼        │
   bugünkü ajan yürüyüşü
                    ▼
   ClaimCheck ─► karar ─► AiRunCache.settle:
        GEÇTİ ve (MISS ya da devralma) → adımlar yazılır / yenilenir
        HIT + GEÇTİ  → success_count++
        GEÇMEDİ      → failure_count++, cache yerinde kalır
```

### 29.2 Ne saklanır, ne saklanmaz

Saklanan, **sayfanın yazdığı adlardır**: `StepRunner.Step` = eylem + ad + değer.
MCP'nin referansları (`e42`, `f3e747`) **saklanmaz**, çünkü bir sayfa
yüklemesine aittirler. Bant 2628c4fd'de "Fatura İptal Et" aynı yürüyüşte üç
farklı referans aldı.

| Kaynak | Nasıl okunur |
|---|---|
| `do_steps` partisi | Partinin kendi adımları (`StepJournal`): `row "Ödenecek"` gibi. Bantta bu tıklama yalnızca bir referanstır; anlamı partide yazar |
| Ajanın tek çağrısı | Çağrının yapıldığı sayfada çözülür: liste aç + seçenek → `select`, ızgara satırı → `row <satırı tanıtan hücre>`, düğme/bağlantı → `click`, `set_date` → `date`, `browser_type` → `type` |
| Tarih | Kayıt gününe olan **gün farkıyla** saklanır. Oynatırken bugün + fark kullanılır; tarih çürümez |
| REDDEDİLEN / HATA alan çağrı | Atlanır: hiçbir şey yapmamıştır |
| Çözülemeyen çağrı (`browser_handle_dialog`, adı okunamayan tık, …) | **Bütün yürüyüş cache'e yazılmaz**, neden konsolda. Bir adımı sessizce atlayan replay başka bir senaryodur |

### 29.3 Ne zaman kullanılmaz (MISS)

| Durum | Konsol |
|---|---|
| Kayıtlı bir senaryodan gelmeyen koşu (Ekran Keşfi) | `ai-cache yok: kayıtlı bir senaryodan gelmeyen koşu cache kullanmaz` |
| Senaryonun kayıtlı akışı yok | `ai-cache MISS: bu senaryonun kayıtlı akışı yok` |
| Senaryo metni kayıttan sonra değişti (doğal dil düzenlendi) | `ai-cache MISS: senaryo metni kayıttan sonra değişmiş` |
| Ekran koddan açılamadı | `ai-cache MISS: ekran koddan açılamadı, kayıtlı akış oynatılmadı` |
| Analist "Cache'siz, ajanla baştan" bastı (`ignoreCache: true`) | `ai-cache atlandı: analist ajanla baştan istedi` |

Metnin özeti, UI'nin gönderdiği senaryo metninin (`text`) SHA-256'sıdır.
Başta ve sonda kalan boşluklar yok sayılır. Adımlar, Beklenen, ön koşul ya
da ad değişirse özet değişir.

### 29.4 Oynatma durursa: ajan devralır

Oynatma şu iki durumda durur:
- bir adım tutmazsa (`StepRunner` "YAPILMADI" / "HATA" / "REDDEDİLDİ" der: ad
  sayfada yok, satır artık yok, seçim tutmadı, onay engeli…);
- **tırnaklı** bir adım içi beklenti sayfada görünmezse.

Durduğu anda ajan o sayfadan başlar. Ajana giden ilk mesajın başında şunlar
yer alır: koddan yapılan adımlar, hangi adımda neden durulduğu ve "Yapılmış
adımları TEKRARLAMA". Sonuca bir uyarı eklenir: "Kayıtlı akış k. adımda durdu
(…); ajan oradan devraldı." Ajan geçerse cache, oynatılan adımlar ve ajanın
adımlarıyla birlikte **yenilenir**. Verisi değişen bir satır (örneğin iptal
edilmiş fatura) böylece kendini onarır.

Oynatma sırasında ekranda yeni bir uyarı belirirse (dialog, "… giriniz.")
oynatma durmaz. Uyarı konsola ve karar turuna "Ekranın yol boyunca
söyledikleri" başlığıyla yazılır; değerlendirmeyi model yapar.

### 29.5 Konsol

Her karar ve her adım sunucu konsoluna `[uptest] ai-cache` önekiyle yazılır:

```
[uptest] ai-cache HIT: 4 adım (bant 6955433b-…, 2 başarılı / 0 başarısız tekrar)
[uptest] ai-cache    select "Firma Seçimi" = "ASAŞ ALÜMİNYUM SAN."
[uptest] ai-cache    row "Ödenecek"
[uptest] ai-cache    click "Fatura İptal Et"
[uptest] ai-cache    click "Geri"
[uptest] ai-cache oynatılıyor: 4 adım, model YOK
[uptest] ai-cache 1/4 select "Firma Seçimi" = "ASAŞ ALÜMİNYUM SAN." → tamam
[uptest] ai-cache 2/4 row "Ödenecek" → tamam (HLLSRC03 Ödenecek)
[uptest] ai-cache 3/4 click "Fatura İptal Et" → tamam
[uptest] ai-cache    beklenen ""Fatura İptal" ekranı açılır" → görüldü (kodla, 0 token)
[uptest] ai-cache 4/4 click "Geri" → tamam
[uptest] ai-cache bitti: 4/4 adım koddan yapıldı
[uptest] ai-cache karar turu: tek AI cagrisi, talimat 2961 karakter
[uptest] ai-cache kayıtlı akışla koşu GEÇTİ; akış yerinde kalıyor
```

Aynı satırlar HTTP cevabında `cache.log` olarak döner. UI'de AI ÖZET'in
üstündeki satırın **"Adımlar"** düğmesi bunları gösterir.

### 29.6 Cevap

```json
"cache": {
  "state": "hit",            // none | miss | ignored | hit | takeover
  "note": "4 adım, kaynak bant 6955433b-…",
  "stepCount": 4,
  "stoppedAt": 3,            // yalnız takeover'da
  "log": ["oynatılıyor: 4 adım, model YOK", "1/4 select …", "…"],
  "saved": "4 adım kaydedildi" // ya da "kaydedilmedi: <neden>"
}
```

### 29.7 Tablo: `AI_RUN_CACHE`

`scenario_id` (birincil anahtar), `scenario_path`, `text_hash`, `steps_json`,
`step_count`, `source_tape_id`, `cif`, `created_at`, `last_used_at`,
`last_validated_at`, `hit_count`, `success_count`, `failure_count`.

Bu yeni bir tablo; `ddl-auto=update` onu ilk açılışta kurar. `SCENARIO`
tablosuna bir foreign key ile bağlı değildir. Silinen bir senaryonun satırı
kalır ama kimse onu aramaz.

### 29.8 Bilinen sınırlar

- **Tutar** (`enter amount`, iki kutu) oynatılırken etiketle bulunamayabilir.
  O zaman o adımda durulur ve ajan devralır.
- Yalnız **son geçen** yürüyüş saklanır; geçmişi tutulmaz.
- Test kullanıcısı (`cif`) anahtarın parçası değildir. Başka bir kullanıcı
  başka satırlar görürse satır adımında durulur ve ajan devralır.
- Ajanın `do_steps` dışında, adı okunamayan bir öğeye yaptığı tek bir tık
  bütün yürüyüşü cache'siz bırakır. Neden konsolda `kaydedilmedi:` satırında
  yazar.

---

## 30. Adım içi Beklenen

### 30.1 Neden

BEKLENEN bugüne kadar yalnızca koşunun **sonunda** kontrol ediliyordu. Bu
yüzden ara bir durum hiç kontrol edilemiyordu: "Fatura İptal Et"in açtığı
pencere, "Geri"ye basıldığında artık ekranda yoktur.

### 30.2 Nasıl yazılır

Adımlar alanında, bir adımın **hemen altına** `Beklenen:` ile başlayan bir
satır yazılır:

```
"Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
Ödeme Durumu "Ödenecek" olan ilk satırı seç
"Fatura İptal Et" bağlantısına tıkla
Beklenen: "Fatura İptal" ekranı açılır
"Geri" düğmesine tıkla
Beklenen: "Firma Fatura İşlemleri" listesi yeniden görünür
```

Ayrı **Beklenen** alanı yerinde kalır ve senaryonun sonucunu tutar.
Veritabanı değişmedi: bu satırlar `steps` kolonunda durur ve bir okuma
kuralıyla ayrılır. Eski senaryolar aynen çalışır.

### 30.3 Kim ne yapar

| Yer | Ne olur |
|---|---|
| `docAsText` (UI) | Beklenen satırı **numara almaz**, önceki adımın altına girintili yazılır: `   Beklenen (bu adımdan hemen sonra kontrol et): …` |
| `StepExpectations` | Bu biçimi okur. Eski UI'nin numaraladığı `3. Beklenen: …` satırını da aynı şekilde önceki adıma bağlar |
| `StepRunner` (do_steps) | Beklenen'i olan bir adımdan sonra partiyi **kodla durdurur** (`BEKLENEN KONTROLÜ`). Sonraki adım o sayfayı değiştiremez |
| `RUN_SYSTEM` | "A step with a Beklenen line under it is checked RIGHT AFTER that step … If the page does not show it, that step is FAILED." |
| `ClaimCheck` | Beklenen satırını adım saymaz. İçindeki bir "tıkla", basılması istenen bir düğme sanılmaz |
| `CachedWalk` | Tırnaklı ad(lar) o anki sayfada **kodla** aranır (0 token). Görülmezse ajan devralır. Tırnaksız bir beklentide o sayfa saklanır ve karar turuna "k. adımdan HEMEN SONRAKİ ekran" diye eklenir |
| Detay görünümü | Beklenen satırı, adımın altında numarasız ve soluk renkte görünür |

Yazım kuralı her yerde aynı: ekranda görünecek adı **tırnak içinde**,
ekranın yazdığı gibi yazın. Tırnaklı bir beklenti cache'li koşuda kodla
kontrol edilir; tırnaksız olanı model değerlendirir.
