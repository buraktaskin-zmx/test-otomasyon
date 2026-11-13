# AI ile çalıştır — Hızlı çalıştır ve adım içi Beklenen

[← İçindekiler](README.md) · [← Örnek koşu](13-ornek-kosu-2628c4fd.md)

---

## 29. Hızlı çalıştır: kayıtlı araç çağrıları, sayfa okunmadan

### 29.1 İki düğme

| Düğme | Ne yapar | Cache |
|---|---|---|
| **AI ile çalıştır** | Ajan ekranı her zaman baştan gezer (bugünkü yürüyüş) | **Okumaz.** Koşu GEÇTİ ise yürüyüşün araç çağrılarını `AI_RUN_CACHE`'e yazar / yeniler |
| **Hızlı çalıştır** | Kayıtlı araç çağrılarını sırayla, **sayfaya bakmadan** yeniden gönderir; sonucu tek AI turu değerlendirir | **Yalnız onu okur.** Senaryonun güncel metni için geçerli kayıt yoksa düğme **disable** |

```
AI ile çalıştır ─► prelude ─► ajan yürür ─► ClaimCheck ─► GEÇTİ? ─► araç çağrıları cache'e
                                                                    (Hızlı çalıştır açılır)

Hızlı çalıştır ─► kayıt yok? ─► 409 NO_CACHE (tarayıcı açılmaz)
               └► prelude (giriş + menü, kodla)
                   └► her kayıtlı çağrı ─► ham MCP aracı (seçiciyle, ad aranmaz, snapshot yok)
                        ├─ hata ─► DUR: koşu Geçmedi (kodla) ─► cache SİLİNİR ─► tarayıcı kapanır
                        │                                         (Hızlı çalıştır disable)
                        └─ hepsi tamam ─► kontrol sayfaları ─► TEK AI TURU (VERDICT_SYSTEM)
                                                ├─ GEÇTİ  ─► success_count++
                                                └─ GEÇMEDİ (Beklenen görülmedi) ─► cache KALIR
```

**Neden bir adım hatasında silinir ama Beklenen görülmeyince kalır:** bir çağrı
hata veriyorsa (düğme yok, satır yok, veri değişmiş) kayıtlı akış artık o
ekranı anlatmıyordur. Bütün çağrılar gittiyse ama sonuç farklıysa akış
geçerlidir; değişen uygulamanın davranışıdır, ve o kırmızı kalmalıdır.

### 29.2 Ne saklanır: sayfadan bağımsız hedefler

Ajan referanslarla (`e42`) çalışır; bunlar bir sayfa yüklemesine aittir. Ama
MCP her eylemde **çalıştırdığı Playwright satırını** döndürür ve bant onu
tutar:

```
await page.getByRole('link', { name: 'Fatura İptal Et' }).click();
```

`ReplayCalls` bu satırı Playwright'ın kendi seçici biçimine çevirir ve
`target` olarak saklar:

| Bantta (MCP'nin çalıştırdığı) | Saklanan `target` |
|---|---|
| `getByRole('link', { name: 'Fatura İptal Et' })` | `internal:role=link[name="Fatura İptal Et"i]` |
| `getByRole('button', { name: 'Geri', exact: true })` | `internal:role=button[name="Geri"s]` |
| `getByLabel('Fatura No')` | `internal:label="Fatura No"i` |
| `getByText('Detay', { exact: true }).first()` | `internal:text="Detay"s >> nth=0` |
| `locator('#select2-firma-container')` | `#select2-firma-container` |
| `getByRole('row', {…}).getByRole('radio')` | `… >> internal:role=radio` |
| Kuruş kutusu: lira kutusuyla AYNI satır (`getByRole('textbox', { name: 'Toplam Tutar' })`) | `<lira seçicisi> >> xpath=following::input[not(@type="hidden")] >> visible=true >> nth=0` |

**Kuruş kutusu (ölçüldü, bant `705c39be`, 13.11.2026):** kuruş kutusunun
kendi adı yok, MCP onu da "Toplam Tutar" diye yazdı. Kayıtta iki kutu tek
seçiciye düştü ve Hızlı çalıştır "50"yi lira kutusuna yazdı (`100.050`,
`00`). Artık aynı seçici farklı bir kutuya düştüğünde bantta o anki sayfaya
bakılır (`ReplayCalls.isKurusOf`, `StepRunner.kurusBox` ile aynı kural);
ikinci kutu birincinin kuruş kutusuysa "ondan sonraki ilk görünür giriş"
olarak saklanır. Başka çakışmalar (iki listenin ikisi de "Seçiniz") olduğu
gibi kalır.

Her çağrı: araç, `target`, diğer argümanlar (`text`, `values`, `key`, …),
tarih için **kayıt gününe olan gün farkı**, ve o adımın altına yazılmış
Beklenen. Tarih oynatılırken bugün + fark kullanılır; çürümez.

`set_date` kendi tıklamalarını yaptığı için, kutuyu açan Playwright satırı
`set_date`'in sonucuna eklenir ve oradan okunur.

**Saklanmayanlar:** REDDEDİLEN / HATA alan çağrılar, snapshot'lar, prelude.
Kendi araçlarımız (`browser_press_sequentially` kodla sağlandığında,
`set_date`) hatayı istisna olarak değil `### Error …` diye başlayan bir cevap
olarak döndürür. Bunlar da HATA sayılır (`BudgetedTool.reportsError`).
Ölçüldü (13.11.2026, bant `4f5f0598`): ajan Firma Seçimi'ni açtı, yanlışlıkla
kapattı, yazmayı denedi ("odakta kutu yok"), yeniden açıp yazdı ve koşu geçti.
Ama başarısız yazma bantta `OK` göründüğü için kayda girdi, ve Hızlı çalıştır
8 çağrının 3.'sünde durdu.
**Yürüyüşü hiç saklatmayanlar** (neden konsolda `kaydedilmedi:` satırında):
hedefi `aria-ref=…` olan, düzenli ifadeyle bulunan ya da tanınmayan bir
bulucu; `browser_handle_dialog`, `browser_fill_form` (13.11.2026'dan beri ajana
verilmiyor), `browser_navigate`.

**Tırnaklı sonuçlar (ölçüldü, ai-run `6fd2b27e`, 13.11.2026):** kendi
araçlarımızın cevabı (`set_date`, kodla sağlanan `browser_press_sequentially`)
bantta JSON tırnakları içinde durur: `"\"Set to 27/11/2026…\""`. Bunu düz
metin gibi okuyan her yer (`startsWith("Set to")`, satır satır Playwright
kodu) başarılı bir tarihi başarısız saydı. Kod yürütmesi 5. adımda durdu ve
ajan devraldı; "Adım adım" ✗ gösterdi; kutunun satırı da okunamadı. Banttan
sonuç okuyan her yer artık `BudgetedTool.plain` / `DateTool.wasSet` kullanır.

Eski (ada göre arayan) cache satırları okunamaz ve "eski biçimde" sayılır;
bir "AI ile çalıştır" onları yeniden yazar.

### 29.3 Hızlı çalıştır ne YAPMAZ

- Sayfayı okuyup bir şey **aramaz**. Çağrılar arasında yalnız bankanın
  yükleme katmanı beklenir (`BusyPage`, JS ile; snapshot değil).
- Ajan çağırmaz; hata olunca **devralma yoktur**.
- Sayfayı yalnız **kontrol anlarında** okur: Beklenen'i olan bir adımdan
  hemen sonra ve en sonda. Bunlar karar turuna "k. adımdan HEMEN SONRAKİ
  ekran" ve "Son ekran" olarak gider. Beklenen'i "AI ile çalıştır" nasıl
  değerlendiriyorsa öyle değerlendirir: sayfaya bakan bir modelle.
- Tarayıcıyı kullanıcı kapatırsa bu bir cache hatası değildir: koşu "tarayıcı
  kapandı" ile biter, cache **kalır**.

### 29.4 Düğmenin durumu

UI, senaryo seçildiğinde ve her koşudan sonra şunu sorar:

```
POST /api/v1/explore/cache-status {app, product, scenarioPath, text}
→ {available, stepCount, savedAt, reason}
```

`text`, "AI ile çalıştır"ın gönderdiği metnin aynısıdır. 13.11.2026'dan beri
akış metnin tamamına değil **adımlara** bağlıdır (`ScenarioShape`):

| Değişiklik | Akış |
|---|---|
| Başlık, Ön koşul, Beklenen (sondaki ya da adım altındaki) | **Kalır**; Beklenen güncel metinden okunur, adım altı Beklenen yeniden bağlanır |
| Bir adımdaki **tırnaklı** değer (`"ASAŞ ALÜMİNYUM SAN."` → `"SANKO DBS ÖDEMELERİ"`) | **Kalır**; yeni değer, eskisinin durduğu hedefe/argümana konur (`[name="…"]`, `text`, `values`) |
| Değişen değer kayıtlı hiçbir hedefte yok (ör. `"Ödenecek"` → satır, kendi metniyle kaydedilmiş) | **Kapanır**: eski tıklama yeni değeri yapamaz, eski satıra basıp Geçti demek olurdu |
| Adımın kendisi (eylem, sıra, tırnaksız sözcük) | **Kapanır** |

Tırnaksız yazılmış bir değer adımın parçası sayılır: `Firma Seçimi alanından
ASAŞ ALÜMİNYUM SAN. seç` satırında firmayı değiştirmek akışı kapatır. Değerleri
tırnakla yazın. Kayıt anındaki metin `AI_RUN_CACHE.scenario_text`'te durur;
bu kolon olmadan yazılmış (13.11.2026 öncesi) bir satır eskisi gibi metnin
tamamına bağlıdır. Düğmenin `title`'ı nedenini söyler:

| `reason` | Ne demek |
|---|---|
| kayıtlı akış yok | Bu senaryo hiç "AI ile çalıştır" ile geçmedi, ya da son Hızlı çalıştır bir adımda durup akışı sildi |
| adımlar kayıttan sonra değişmiş | Bir adımın eylemi/sırası/tırnaksız metni değişti |
| değişen değer kayıtlı akışta bir hedefe karşılık gelmiyor | Tırnaklı bir değer değişti ama kayıtlı çağrıların hiçbiri onu taşımıyor |
| senaryo kayıttan sonra değişmiş | Eski (metnin tamamına bağlı) bir satır ve metin değişti |
| kayıtlı akış eski biçimde | Önceki, ada göre arayan cache'ten kalma satır |

### 29.5 Konsol

```
[uptest] ai-cache HIT: 6 çağrı (bant 6955433b-…, 2 başarılı tekrar)
[uptest] ai-cache hızlı çalıştır: 6 çağrı kayıttan, sayfa okunmadan
[uptest] ai-cache 1/6 tıkla "Firma Seçimi"  [#select2-firma-container] → tamam
[uptest] ai-cache 2/6 tıkla "ASAŞ ALÜMİNYUM SAN."  [internal:role=option[name="ASAŞ ALÜMİNYUM SAN."i]] → tamam
[uptest] ai-cache 3/6 tıkla "HLLSRC03 Ödenecek"  [internal:role=row[name="HLLSRC03 Ödenecek"i]] → TUTMADI: locator.click: Timeout 5000ms exceeded …
[uptest] ai-cache silindi: 3. adım tutmadı: locator.click: Timeout 5000ms exceeded …
```

Aynı satırlar cevabın `cache.log`'unda; UI'de AI ÖZET'in üstündeki
**"Adımlar"** düğmesi gösterir. Hata ayrıca AI ÖZET'in "Geçemeyen adımlar"
listesinde yazar.

### 29.6 Cevap

```json
"cache": {
  "state": "hit",            // none | walked | missing | hit | stopped
  "note": "6 çağrı, kaynak bant 6955433b-…",
  "stepCount": 6,
  "stoppedAt": 3,            // yalnız stopped'da
  "log": ["hızlı çalıştır: 6 çağrı kayıttan, sayfa okunmadan", "1/6 tıkla …"],
  "saved": "6 çağrı kaydedildi" // AI ile çalıştır'da; stopped'da "kayıtlı akış silindi"
}
```

### 29.7 Tablo: `AI_RUN_CACHE`

`scenario_id` (birincil anahtar), `scenario_path`, `text_hash` (adımların
biçiminin SHA-256'sı), `scenario_text` (kayıt anındaki metin), `steps_json`
(`ReplayCall` listesi), `step_count`, `source_tape_id`, `cif`, `created_at`,
`last_used_at`, `last_validated_at`, `hit_count`, `success_count`,
`failure_count`. `ddl-auto=update` ilk açılışta kurar; `SCENARIO`'ya foreign
key yok.

### 29.8 Bilinen sınırlar

- **İlk doğrulanacak:** MCP 0.0.82'nin `target`'ta `internal:role=…`
  seçicisini kabul etmesi. Kabul etmezse ilk adım `does not match` der.
- Satır adı satırın bütün metnini taşır (`HLLSRC03 Ödenecek 1.250,00 …`). O
  fatura değişince satır adımı tutmaz → akış silinir → bir "AI ile çalıştır"
  yeniden kaydeder. İstenen davranış budur.
- Select2 açıcı bir kimlikle bulunursa (`#select2-…-container`) o kimlik
  sabittir; adla (`Seçiniz`) bulunursa seçili değer değişince tutmayabilir.
- Test kullanıcısı (`cif`) anahtarın parçası değil.

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
| `ReplayCalls.align` | Kayıtta Beklenen'i, o adımın tırnaklı adını taşıyan çağrıya bağlar |
| `FastReplay` | O çağrıdan hemen sonra sayfayı alır; karar turu onu "k. adımdan HEMEN SONRAKİ ekran" olarak görür |
| Detay görünümü | Beklenen satırı, adımın altında numarasız ve soluk renkte görünür |

Yazım kuralı her yerde aynı: ekranda görünecek adı **tırnak içinde**,
ekranın yazdığı gibi yazın. Tırnaklı ad, Beklenen'in hangi adıma ait olduğunu
Hızlı çalıştır'da da belirler.
