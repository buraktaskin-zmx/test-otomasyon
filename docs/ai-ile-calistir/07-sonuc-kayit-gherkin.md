# AI ile çalıştır — Sonuç, kayıt ve Gherkin

[← İçindekiler](README.md) · [← do_steps ve set_date](06-do-steps-ve-set-date.md) · [Ayarlar ve sabitler →](08-ayarlar.md)

---

## 17. Sonuç: report_result, RunVerdict, ClaimCheck

### 17.1 report_result şeması (`RunVerdict`)

| Argüman | Tip | Modele söylenen |
|---|---|---|
| `succeeded` | `List<String>` | Yapılan ve doğru davranan her adım, Türkçe bir cümle |
| `failed` | `List<String>` | Yapılamayan, atlanan ya da BEKLENEN'i göstermeyen her adım |
| `warnings` | `List<String>` | Sonucu belirlemeyen ama bilinmesi gerekenler |
| `expectations` | `List<String>` | BEKLENEN LİSTESİ'nin her satırı için bir madde: `B1 GÖRÜLDÜ: …` / `B1 GÖRÜLMEDİ: …` |
| `passed` | `boolean` | Yalnız her adım yapıldı VE her BEKLENEN görüldüyse true |

- Beş argüman da **zorunludur**. Model şemanın istediğini doldurur, izin
  verdiğini atlar. "Hiç yok" `[]` ya da `""` ile söylenir.
- `returnDirect = true`: sonuç modele değil çağırana döner. Sonraki tur
  olmaz. Model yine bir şey çağırırsa kapı `REPORTED` der.
- **Esnek okuma** (`acceptJson`): Spring'in yansımalı bağlaması devre
  dışıdır. Dizi, string'e yazılmış dizi ya da tek cümle kabul edilir;
  `passed` için true / "true" / "evet" geçerlidir. Ölçüldü (11.11.2026): ajan
  dizileri string olarak yazmıştı, yansıma fırlattı ve biten bir yürüyüş
  hiçbir şey bildiremedi. Ölçüldü (13.11.2026, bant `d7b61934`, Hızlı
  çalıştır'ın karar turu): model bütün cevabı `succeeded`'in metninin İÇİNE
  yazdı (`{"succeeded": "[…], \"failed\": […], \"expectations\": […],
  \"passed\": true}"}`); okunduğu gibi `expectations` ve `passed` yoktu, sonuç
  "0/5 beklenen cevaplandı" ile Geçmedi oldu. `RunVerdict.repaired`: bir alanın
  metni kendi anahtarının ardına konunca birden çok anahtarlı bir nesne
  oluyorsa o nesne okunur.

### 17.2 Karar nasıl hesaplanır

```
                    ajanın report_result'ı
                             │
                             ▼
              Result(succeeded, failed, warnings, claimedPassed)
                             │
     ┌───────────────────────┼─────────────────────────────────────────┐
     │ kodun eklediği uyarılar (verdict.warn)                          │
     │   • "Ekran koddan açılamadı, ajan kendisi açtı (…)"              │
     │   • "Token bütçesi aşıldı: 104.233 / 100.000."                   │
     ├─────────────────────────────────────────────────────────────────┤
     │ ClaimCheck.apply  (bant ↔ iddia)                                 │
     │   senaryodaki her numaralı adım için:                            │
     │     katlanmış metinde "tıkla/tıklayın/bas/basın" var mı?         │
     │       (tıklama / basma YOK SAYILIR: olumsuz cümle)                │
     │     fiilden ÖNCEKİ SON tırnaklı ad = basılması istenen           │
     │   bantta o adı taşıyan BAŞARILI bir tık var mı?                  │
     │     kaynaklar: render edilmiş `click "X"` satırları,              │
     │                Playwright kodundaki name: 'X' / getByText('X'),  │
     │                çağrının element argümanı,                        │
     │                tıklanan ref'in önceki sayfadaki adı ve ETİKETİ   │
     │                (0b6fc87b: "Döviz Cinsi" listesi ref ile açıldı)  │
     │     karşılaştırma: katlanmış + boşluksuz, "içerir"                │
     │   yoksa → verdict.refute(ad, "\"X\" düğmesine tıklanmadı: …")     │
     │          → o adı anan "succeeded" satırları ÇIKAR                 │
     │          → hata "failed"a EKLENİR                                 │
     ├─────────────────────────────────────────────────────────────────┤
     │ ExpectationCheck.apply  (son Beklenen ↔ son sayfa)               │
     │   sondaki her Beklenen maddesinin adlarını çıkar                 │
     │     (tırnaklı adlar; yoksa "A, B ve C alanları" listesi)          │
     │   son sayfada OLMAYAN ama önceki bir sayfada OLAN ad ≥ 2 mi?      │
     │     ("kapanır", "görünmez", "yok", "değil" diyen madde atlanır)  │
     │   evet → verdict.misplaced(B-no, "… son ekranda yok; …")         │
     │   HER Beklenen (adım altı da): adı bu koşunun HİÇBİR sayfasında   │
     │     yoksa → verdict.unseen(B-no, "\"X\" adı … hiçbir ekranda      │
     │     geçmedi") — yalnız tırnaklı adlar ya da "… alanları" listesi  │
     │   ajanın kendi "B2 …" satırları atılır (kod zaten yazıyor)         │
     ├─────────────────────────────────────────────────────────────────┤
     │ Beklenen'lerin kodla kapanması (RunVerdict.settleExpectations)   │
     │   StepExpectations.all: adım altındakiler + son liste → B1, B2…  │
     │   her B için ajanın cevabı (B-numarasıyla, yoksa sırayla):       │
     │     misplaced   → failed  "Beklenen (…) kontrol edilemedi: …"    │
     │     cevap yok   → failed  "Beklenen (…) kontrol edilmedi: …"     │
     │     GÖRÜLMEDİ / durumsuz → failed "Beklenen (…) görülmedi: …"    │
     │     GÖRÜLDÜ     → succeeded "Beklenen (…) görüldü: … — kanıt"    │
     └─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
       passed() = claimedPassed  VE  failed boş       →  "PASSED" / "FAILED"
```

**Neden var (ölçüldü, 12.11.2026):** "İptal ekranından Geri ile listeye
dönülür" senaryosunda ajan altı hamle yaptı ve dört adımın hepsini
"yapıldı" diye bildirdi; "Geri düğmesine tıklandı ve liste ekranına dönüldü"
de bunlar arasındaydı, `failed: []`. Analist tarayıcıyı izliyordu: ajan
ikinci bir faturayı seçmişti ve pencere kapandı. "Yaptım" diyen ama yapmayan
bir ajan yeşil sonuç alıyordu.

**Beklenen neden kodla kapanıyor (ölçüldü, bant `bd40c3ee`, 12.11.2026):**
"Güncelleme ekranındaki yeni değer alanları düzenlenebilir". Son Beklenen'in
iki maddesi vardı: üstteki alanlar salt okunur, alttaki "Yeni …" alanlarının
"hiçbiri düzenlenebilir değildir". İkinci madde bilerek yanlış yazılmıştı:
sayfada `textbox "Yeni Fatura No"`, `textbox "Yeni Fatura Tutarı"` vardı, yani
alanlar düzenlenebilirdi. Ajanın `report_result`'ı beş adımı saydı, Beklenen'e
hiç değinmedi ve `failed: []`, `passed: true` ile bitti; koşu **Geçti**
gösterildi. Ayrıca son adım "Geri" olduğu için son sayfa listeydi; "Kurum Adı",
"Bayi Ünvanı", "Para Birimi" orada yoktu. Bu kurallar prompt'ta zaten
yazıyordu. Bugünkü kod aynı bandı **Geçmedi** sayar: B1 ve B3 cevapsız, B2 ise
yanlış yerde (`ExpectationCheckTest`, ve gerçek bant üzerinde ayrıca denendi).

**Hiçbir ekranda olmayan ad (ölçüldü, 13.11.2026):** "Güncelleme formunda yeni
değer alanları bulunur". Analist Beklenen'e bilerek "Yeni Son banko Tarihi"
yazdı; ekranda "Yeni Son Ödeme Tarihi" var. Model maddeye "görüldü" dedi ve
kanıt olarak Beklenen cümlesini aynen geri yazdı. Bir ad koşunun hiçbir
sayfasında yoksa görülmüş olamaz; artık bunu kod söylüyor. Ad çıkarılırken
adın içindeki küçük harfli sözcük atılmaz ("Yeni Son banko Tarihi" bütün
kalır; hata zaten o sözcükte olabilir). Serbest bir cümleden ("Hata mesajı
görünür: …") ad çıkarılmaz, çünkü orada kod bir hata uydurmuş olur.

**ClaimCheck'in kontrol ETMEDİKLERİ:**

| Kontrol edilmeyen | Neden |
|---|---|
| "seç", "yaz", "gir" adımları | Yalnız basma fiilleri bakılıyor. Bir seçimin **olduğu** banttan okunabilir ama doğru değerin seçildiği ayrı bir iş |
| BEKLENEN'in gerçekten ekranda görüldüğü | Sayfadan değer okuyup karşılaştırmak gerekir. Bu yol bir doğrulama adımı atmaz. Kod yalnız şunları zorlar: her Beklenen'e bir cevap verilmesi, ve son Beklenen'in adlarının son sayfada bulunması (yukarıdaki `ExpectationCheck`). "GÖRÜLDÜ" cevabının doğru olup olmadığına yine model karar verir |
| Tırnaksız adlar | Adı okunamayan adım tahmin edilmez, olduğu gibi bırakılır |

---

## 18. Cevap ve AI ÖZET

```json
200 OK
{
  "answer":  "Doğru ilerleyen adımlar:\n- …\n\nGeçemeyen adımlar:\n- …\n\nUyarılar:\n- …\n\nSonuç: Geçmedi ✗",
  "summary": { "succeeded": [...], "failed": [...], "warnings": [...], "verdict": "FAILED" },
  "tapeId":  "a73ead34-…",
  "gherkin": "navigate to \"NAKİT YÖNETİMİ\" \"Firma Fatura İşlemleri\"\nselect \"…\" from \"Firma Seçimi\"\n…",
  "notes":   ["#12 browser_press_key için vocabulary'de karşılık yok, satır üretilmedi."],
  "cache":   { "state": "miss", "note": "kayıtlı akış yok", "stepCount": 0, "log": [], "saved": "4 adım kaydedildi" },
  "traceId": "…"
}
```

**`walk` — "Adım adım" (WalkStory).** AI ÖZET'in en üstündeki girintili
anlatım: giriş, menü, her ekran (başlığıyla; "yeni ekran" / "geri dönüldü"),
o ekranda yapılan her eylem, ve eylemden sonra ekranın gösterdikleri (açılan
pencere/uyarı, "… giriniz." mesajı, ızgaradaki satır sayısı). **Banttan,
kodla** üretilir: 0 token, ajanın iddiası değil olanın kendisi. Reddedilen ve
başarısız eylemler de yazılır (⊘ / ✗). 80 satırda kesilir.

```json
"walk": [
  {"depth":0, "kind":"ok",     "text":"Giriş yapıldı (CIF QZ06395288)"},
  {"depth":1, "kind":"screen", "text":"Firma Fatura İşlemleri"},
  {"depth":2, "kind":"ok",     "text":"\"Firma Seçimi\" listesinden \"ASAŞ ALÜMİNYUM SAN.\" seçildi"},
  {"depth":3, "kind":"seen",   "text":"görüldü: ızgarada 2 satır"}
]
```

| Durum | HTTP | UI'da |
|---|---|---|
| Sonuç bildirildi | 200 + `summary` | AI ÖZET: üç liste (boş olan gizli) + ✓ Geçti / ✗ Geçmedi |
| Ajan report_result çağırmadı | 200, `summary` yok | Ajanın düz yazısı + "Sonuç bildirilmedi — ajan yapılandırılmış bir sonuç vermedi." |
| Giriş / ekran yüklenmedi | 502 `"IllegalStateException: Ajan başlatılmadı: …"` | Toast "Ajan çalıştırılamadı: …" |
| Tarayıcı kapatıldı | 409 `BROWSER_CLOSED` | "Tarayıcı kapandı" durumu |
| Başka bir hata | 502 `"Sınıf: mesaj"` | Toast; kök sebep ve yığın izi **sunucu logunda** |

Her durumda `tapeId` ve `traceId` döner. Bozulan koşu, kaydı okunmaya en çok
değen koşudur.

---

## 19. Bant (tape.json)

**Dosya:** `ActionTape.java`. **Yer:** `runs/mcp/<tapeId>/tape.json` (mutlak
yola çevrilir; `runs/` gitignore'dadır). **Her kayıttan sonra yeniden
yazılır.** Ekran 3'te ölen bir yürüyüş, ekran 1 ve 2'yi diskte bırakır.

```json
{
  "tapeId": "a73ead34-…",
  "kind": "explore",
  "recordedAt": "2026-11-12T11:02:10Z",
  "url": "https://ifctest.yapikredi.com.tr/ngc/indexNgc.do",
  "mainMenu": "NAKİT YÖNETİMİ",
  "instruction": "<compose() çıktısı, şifre ve SMS maskeli>",
  "finishedAt": "…",
  "entryCount": 23,
  "entries": [
    {"seq":1, "atMs":4120,  "tool":"prelude_login",   "args":"{\"app\":\"NGC\",\"cif\":\"…\",\"userId\":\"…\"}", "outcome":"OK", "result":"Giriş yapıldı", "durationMs":4100},
    {"seq":2, "atMs":9800,  "tool":"prelude_menu",    "args":"{\"mainMenu\":\"NAKİT YÖNETİMİ\",\"screen\":\"Firma Fatura İşlemleri\"}", "outcome":"OK", "result":"… açıldı"},
    {"seq":3, "atMs":11400, "tool":"browser_snapshot","args":"{}", "outcome":"OK", "result":"[{\"text\":\"### Page …\"}]"},
    {"seq":4, "atMs":15200, "tool":"do_steps",        "args":"{\"steps\":[…]}", "outcome":"OK", "result":"1. select … → tamam …"},
    {"seq":5, "atMs":15900, "tool":"browser_click",   "args":"{\"target\":\"f3e422\",\"element\":\"Firma Seçimi\"}", "outcome":"OK", "result":"…"},
    {"seq":6, "atMs":17500, "tool":"browser_snapshot","args":"{}", "outcome":"OK", "result":"… (eylemden sonra kodun aldığı sayfa)"},
    {"seq":9, "atMs":30000, "tool":"browser_click",   "args":"{\"target\":\"f3e900\"}", "outcome":"REFUSED", "result":"REFUSED: \"evet\" commits something …"},
    {"seq":23,"atMs":61000, "tool":"report_result",   "args":"{\"succeeded\":[…],\"failed\":[…],…}", "outcome":"OK", "result":"Sonuç kaydedildi. Görev bitti."}
  ]
}
```

- `outcome`: `OK` / `ERROR` / `REFUSED`.
- `result` **tam**, kesilmemiş sonuçtur. Model daha azını gördü, bant
  kanıttır.
- `do_steps` kaydı alt adımlarından **sonra** düşer, çünkü alt adımlar kendi
  `browser_click`/`browser_snapshot` kayıtlarını onun içinde, çalıştıkları
  anda yazar.
- **Maskeleme** (`SecretMask`): şifre ve SMS kodu yalnız **bir argüman
  değerinin tamamı** olduklarında maskelenir. İlk sürüm her geçtiği yerde
  maskeliyordu ve test şifresi `123456` olduğu için `81234567` bayi kodu
  `8***7` oldu. CIF ve userId maskelenmez.

En son bantı bulmak (Mac / Git Bash):

```bash
ls -t runs/mcp/*/tape.json | head -1
```

---

## 20. Gherkin oluştur

Koşu bitince AI ÖZET'in altında **"Gherkin oluştur"** düğmesi çıkar. Bu
**ikinci ve isteğe bağlı** bir model çağrısıdır: kötü giden bir yürüyüş için
hiç ödenmez.

```
POST /api/v1/explore/gherkin {tapeId, app, flow, mainMenu, scenario, expected}
  │
  ├─ TapeFile.read(tapeId)                      bant yoksa → 404
  ├─ draft = TapeRenderer.render(bant)           KURAL TABANLI taslak (kod, model yok)
  │     prelude_menu      → navigate to "ANA" "Ekran"
  │     browser_click     → click / select row / select … (Playwright satırı + snapshot ile çözülür)
  │     set_date          → enter date "Alan" "gg/aa/yyyy"
  │     SILENT            → snapshot, find, wait, navigate, report_result, prelude_login, do_steps
  │     karşılığı yoksa   → satır YOK, notes'a yazılır ("vocabulary'de karşılık yok")
  │     THEN              → analistin BEKLENEN satırlarından (ExpectedVerifies), ajanın gözleminden DEĞİL
  ├─ sözlük yok (executor kapalı) → draft + not ile dön
  ├─ user = PromptBuilder.fromWalk(katalog, senaryo, beklenen, TapeEvidence.digest(bant), draft, notlar)
  ├─ AiTraces.open("gherkin", parent = yürüyüşün oturumu)
  ├─ SuggestService.suggestFromWalk(...) + Provenance.checkAgainstWalk
  │     tırnaklı her metin bantta, katalogda ya da analistin metninde GEÇMELİ (uydurma yok)
  └─ { gherkin, notes, valid, attempts, source:"ai", traceId }   model hatasında → draft + neden
```

Çıkan Gherkin bir **öneridir**. Analist kaydetmeden hiçbir yere yazılmaz.

---

## 21. Token Takibi ile ilişki

Her koşu bir AI oturumudur: `AiTraces.open("ai-run", …)`, başlık
`scenarioPath`, `tapeId` bağlı. Her model turu bir `AI_TURN`, her araç
çağrısı bir `AI_TOOL_CALL` kaydıdır. Prelude'un `prelude_login` /
`prelude_menu` kayıtları da oraya düşer. Tam metinler `runs/llm/<id>/`
altındadır. Ayrıntı: `docs/token-takibi/README.md`.

| Soru | Nereye bakılır |
|---|---|
| Ajana ilk ne gitti? | Token Takibi → oturum → tur #1 → "Bu turun tam girdisini göster" → `kullanıcı` (`docs/token-takibi/README.md` §3b "tek metin") |
| Bir turda model neyi gördü? | Aynı görünüm. Tool sonuçları kısaltılmış (`[eski ekran kısaltıldı]`) halleriyle |
| Bir araç tam olarak ne döndü? | Bantta `result` (tam) ya da trace'in "seen" metni (modelin gördüğü) |
| Bütçe aşıldı mı? | Oturum kartı "Bütçe", listede "bütçe aşıldı" |
| Gherkin oluşturmanın maliyeti | Yürüyüş oturumunun altında `gherkin` türünde alt oturum |

Bütçe sayacı `AiTraces.currentTokens()` → o oturumun o ana kadarki toplam
token'ı. `AiRunPolicy.spentOut()` bunu okur.
