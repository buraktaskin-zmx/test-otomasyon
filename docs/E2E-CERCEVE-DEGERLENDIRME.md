# tester.army `e2e` Değerlendirmesi — Uyarlar mıyız, Kendimiz Yazabilir miyiz?

Tarih: 2026-10-04 · Durum: **karar verildi, uygulama bekliyor**

Bu dosya bir **değerlendirme ve uygulama planıdır**. Dışarıdan bir açık kaynak
çerçeveyi inceleyip, hangi fikrini aldığımızı, hangisini almadığımızı ve
neden'ini kayda geçirir. İlgili diğer belgeler:

- Hedefler ve yol haritası: [`PROJECT.md`](../PROJECT.md)
- Test tarafı kuralları: [`CLAUDE.md`](../CLAUDE.md)
- Sunum anlatısı: [`docs/SUNUM-BAGLAMI.md`](SUNUM-BAGLAMI.md)

---

## Context — bu değerlendirme neden yapılıyor

`tester.army/e2e` (GitHub: `tester-army/e2e`, Apache-2.0, ~2.1k yıldız) açık
kaynak bir "agentic E2E test" çerçevesi. İlgi çeken tarafı bizim yaptığımız işle
aynı problemi farklı bir yerden çözmesi: **deterministik adımlarla AI adımlarını
aynı testte karıştırması**, ve geçen AI adımlarını **cache'leyip bir sonraki
koşuda modeli hiç çağırmadan replay etmesi**.

Soru ikiye ayrılıyor ve cevapları birbirinden bağımsız:

1. **Çerçeveyi alır mıyız?** — Hayır. Gerekçe aşağıda, kısa.
2. **Onun hibrit fikrini kendi yığınımızda kendimiz kurabilir miyiz?** — Evet,
   ama tamamını değil. Hangi parçasının bizde zaten olduğu, hangisinin gerçekten
   eksik olduğu ve hangisinin bizim için **zararlı** olduğu bu dosyanın asıl
   konusu.

---

## 1. e2e tam olarak ne yapıyor (doğrulanmış)

| | |
|---|---|
| Dil / çalışma zamanı | TypeScript, Node.js ≥ 22.12, `npx e2e run` |
| Tarayıcı motoru | Playwright (Chromium/Firefox/WebKit) |
| Mobil | iOS simulator + Android emulator (`agent-device`) |
| Lisans | Apache-2.0 |
| Olgunluk | 1.0 öncesi; "APIs and config can still change between minor releases" |

Test şöyle yazılıyor — deterministik ve agentic adımlar **yan yana**:

```typescript
test('a member upgrades to Pro', async ({ app, agent, screen }) => {
  await app.open('/settings/billing');
  await agent.act('upgrade the workspace to the Pro plan');          // AI
  await agent.assert('the invoice preview shows a prorated amount'); // AI
  await expect(screen.getByRole('status')).toContainText('Pro');     // deterministik
});
```

### Cache mekanizması (en değerli fikir, mekaniği önemli)

- Bir `agent.act` adımı **ancak sonrasındaki bir doğrulama onu kanıtlarsa**
  kaydediliyor. "Geçti" yetmiyor, etkisinin görülmüş olması gerekiyor.
- Kaydedilen: eylem sırası; hedef elemanın **role/name/testID + çevresi**;
  yazılan değerler (secret'lar sadece isimle); ve bitiş durumu — en fazla 8
  "beliren", 8 "kaybolan" kontrol, state değişimleri, route, alert.
  **Kaydedilmeyen:** prompt, konuşma geçmişi, ekran görüntüsü, DOM ağacı.
- Konum/format: `.e2e/cache/` altında, anahtarın hash'i ile adlandırılmış JSON.
  Anahtar = test adı + hedef + talimat metni (hash) + parametreler + agent adı +
  agent context (hash) + motor major/minor sürümü.
- Replay geçerli sayılır: başlangıç route'u eşleşirse (ID ve timestamp'ler
  normalize edilerek), her kayıtlı kontrol 15 sn içinde **tek anlamlı** şekilde
  bulunursa, ve beklenen değişimlerden **en az biri** gerçekleşirse.
- Eşleşmezse: adım durur ve **canlı agent'a devredilir** —
  `step.cache.mode: 'agent-concluded'`, `step.cache.reason: target-not-found |
  action-failed | end-mismatch`. Agent adımı bitirir ve yeniden kaydedebilir.
- `--strict-cache`: devretme yok, `REPLAY_STALE` + exit 2 ile **gürültülü
  patlar**.
- CI'da cache varsayılanı **read-only**: replay çalışır, yeni kayıt yazılmaz.

Tek cümlelik vaadi: *"token harcaman testlerini ne sıklıkla çalıştırdığına
değil, UI'ının ne sıklıkla değiştiğine bağlı olur."*

### Decision models (ikinci değerli fikir)

Tam LLM agent döngüsü yerine iki kademeli, **serbest metin üretmeyen** bir yapı:

- **Decision model** — "hangi işlem, hangi eleman?" sorusuna, sayfanın semantik
  ağacından **önceden kurulmuş sonlu bir seçenek kümesi** üzerinden cevap verir
  (olasılık dağılımı döndürür). Halüsinasyon selector, uydurma URL, alakasız
  tuş basışı **yapısal olarak** imkânsız.
- **Text model** — sadece `type` eylemi seçildiğinde alana yazılacak değeri
  üretir. Küçük bir model yeter.
- `minProbability` / `minConfidence` eşikleri ile kapı konabiliyor.
- Dokümanın kendi tavsiyesi: görü (vision) ve uzun muhakeme gerektirmeyen,
  **deterministik ve denetlenebilir** otomasyon istiyorsan bunu kullan.

### Diğer kayda değer parçalar

- MCP sunucusu + agent skill: Claude Code/Cursor uygulamayı gezip gerçek UI'dan
  alınmış locator'larla test yazabiliyor. (Bizim `orchestrator/explore/`
  paketimizin yaptığı işin aynısı.)
- Test başına **failure page**: her adımın eylemleri, poll'ları, **cache kararı**
  ve uygulama log'u.
- Model esnekliği: `@ai-sdk/openai-compatible` ile kendi vLLM'imize
  bağlanabiliyor (`baseURL`, `apiKey`, `name`).
- Playwright / Cypress / Selenium / Detox / Maestro'dan **migration kılavuzları**.

---

## 2. Biz zaten aynı eksenin neresindeyiz

Bu değerlendirmenin en önemli tespiti ve geri kalan her şeyin dayanağı:

> **e2e'nin cache'i, bir agent koşusundan türetilmiş deterministik bir
> script'tir. Bizim `.feature` dosyamız da bir modelden türetilmiş deterministik
> bir script'tir. Aynı şeyin iki farklı biçimi.**

Fark, modeli **ne zaman** çağırdığımızda:

| | e2e | bizim platform |
|---|---|---|
| Model ne zaman çalışır | **koşu anında** (ilk koşu), sonra cache | **yazım anında** (`SuggestService`), koşuda hiç |
| Türetilen artefakt | `.e2e/cache/*.json` — eylem izi | `.feature` — Gherkin |
| Artefaktı kim okur | kimse (makine formatı) | **iş analisti** (okur, düzenler, onaylar) |
| Artefakt doğrulanır mı | hayır, sadece replay edilir | evet — `ScenarioValidator`, ~50 ms, katalog farkındalıklı |
| Uygulama değişince | agent devralır, yeni iz kaydeder | koşu **patlar**, insan düzeltir |

Yani bizim mimarimiz e2e'nin cache'ini **bir adım ileri götürmüş** hâli: ara
ürünü makine formatı yerine insanın okuyup onaylayabileceği bir dile çevirmişiz.
Bankada denetlenebilirlik (`PROJECT.md` → "Greater traceability and
auditability") bunun karşılığında alınan şey.

**Bizde olmayan tek gerçek yetenek: koşu anında devralma.** e2e'de uygulama
değişince agent devreye girip yolu bulur. Bizde adım patlar.

---

## 3. Karar: çerçeveyi almıyoruz

| Gerekçe | Ayrıntı |
|---|---|
| **Yığın uyuşmazlığı** | TypeScript/npm ↔ Java/Maven/Spring. Validator, katalog modeli, vocabulary üreteci, orchestrator, executor — hepsi Java. Geçiş Faz 1, 1.5 ve 2'yi çöpe atmak demek. |
| **Gherkin kaybı** | e2e testleri TypeScript. Deliverable #2 ("kod yazmayan analist katkı verir") ve analistin inceleyip düzenlediği katman yok olur. Projenin varlık sebebi bu. |
| **Vision zorunluluğu** | Yerleşik agent **tool calling + görü (image)** destekleyen model istiyor. Bizim `qwen3-6` metin modeli; Endpoint B'nin tool-call parser'ı zaten çağrıları bozuyor. Varsayılan agent bizde çalışmaz. |
| **`agent.assert` denetlenemez** | Bir bankanın regresyon suite'inde pass/fail kararının modelin yargısına bağlanması kabul edilemez. Bizim "sessiz geçiş, patlamaktan kötüdür" kuralının tam tersi. |
| **Olgunluk** | 1.0 öncesi, minor sürümler arasında API kırılıyor. Kurumsal VDI + Docker + iç registry zincirinde taşınacak risk değil. |
| **npm erişimi belirsiz** | Bölüm 6. Bilinmiyor; bilinse bile yukarıdaki beş madde duruyor. |

Lisans (Apache-2.0) bir engel **değil** — fikirleri almak, hatta kod parçası
uyarlamak serbest. Engel teknik ve mimari.

---

## 4. Kendimiz ne kuracağız — üç iş kalemi

Sıralama değer/risk oranına göre. İlk ikisi bağımsız, üçüncüsü ilk ikisinden
sonra anlamlı.

### İş 1 — Decision model: MCP agent'ın serbest tahminini sonlu seçime çevir

**Neden:** `PROJECT.md` → Open Issues'ta duran gerçek bir arıza:

> *"NGC login sayfasında iki `Giriş` linki tek selector'a uyuyor: model aynı
> tahmini onlarca kez tekrarladı, sayfayı yeniden okumak yerine."*

Bu bir prompt sorunu değil, **yapısal** bir sorun: modele serbest metin
sorduğumuz sürece aynı serbest metni tekrar üretebilir. e2e'nin cevabı doğru
cevap — **soruyu çoktan seçmeliye çevir.** İki "Giriş" linki varsa model
"`Giriş`" diyemez, "**1** mi **2** mi" sorusuna cevap verir.

Ek fayda: çoktan seçmeli soru, tool-call parser'ı bozuk Endpoint B'de bile
çalışır. Bugün MCP agent'ı tek bir endpoint'e kilitleyen kısıt gevşer.

**Nerede:**

- `uptest-orchestrator/src/main/java/orchestrator/explore/` — yeni
  `DecisionTool` (veya `BudgetedTool`'un yanında bir katman).
- Akış: `browser_snapshot` sonucundan aday elemanları **numaralandırılmış liste**
  olarak çıkar → `LlmClient.ask(system, "Hangisi? 1/2/3")` → seçilen indeksi
  **ham MCP `browser_click`'e** çevir. Model hiçbir zaman selector yazmaz.
- `DateTool.setDate` zaten tam bu şekli taşıyor: bileşik bir işlemi dört ham MCP
  çağrısına kendisi çeviriyor ve bütçeden bir kez düşüyor. **Aynı kalıbı
  kopyala.**
- `BudgetedTool.refuse()` zincirine yeni bir madde: aynı adaya N'inci kez gelen
  seçim reddedilsin (`BudgetedTool` zaten cümleyle reddediyor, exception
  atmıyor — o kuralı koru).

**Boyut:** orta. ~1 yeni sınıf + `ExploreController.toolsFor` içinde bağlama.

---

### İş 2 — "Patla, sonra açıkla": çözümleyici devralma (healing DEĞİL)

**Neden:** Bizde bir etiket kayınca koşu 30 saniye bekleyip patlıyor ve geriye
"locator bulunamadı" kalıyor. e2e'de agent devralıp yolu buluyor. Ama
**devralmanın bizdeki hâli testi geçirmemeli** — geçirirse projenin güven
temeli çöker (`PROJECT.md`: *"model yeni bir tıklama yolu icat edemez"*).

**Tasarım — ayrımı net tut:**

```
adım patladı
   ↓
DEVRET  (sadece okuma + aday listesi, tıklama YOK)
   ↓
"'Ödeme Tutarı' bulunamadı. Ekranda en yakın 3 aday:
   1. 'Ödeme Tutari'   (fieldcontainer, aynı konum)   ← muhtemel
   2. 'Toplam Tutar'
   3. 'Tutar'
 Katalog ngc/banko-payment.yaml güncel değil."
   ↓
koşu YİNE DE FAILED döner
```

Yani kazanç **teşhis süresinde**, pass/fail kararında değil. Analist raporu
açınca ne olduğunu okur, kataloğu düzeltir, senaryo yeniden doğrulanır. Bu,
"sessiz geçiş patlamaktan kötüdür" kuralını bozmadan healing'in faydasının
büyük kısmını alır.

**Nerede:**

- `PW/common/CommonActions.java` — `findValueElement`, `click`, `enter`
  hâlihazırda fail-fast; bunların attığı `AssertionError`'a **aday listesi**
  eklenecek. Bu kısım tamamen deterministik, model gerekmiyor:
  `page.locator("div[class*='fieldcontainer']")` zaten `CatalogDiscovery`'de
  var, Levenshtein zaten `ScenarioValidator.nearestLabels`'ta var.
- Model çağrısı **isteğe bağlı ikinci katman** olarak orchestrator tarafında
  kalsın; executor'a LLM bağımlılığı sokma (mimari ayrım bozulmasın).
- `server/timeline/StepTimelinePlugin.java` → adaylar step timeline'ına düşsün,
  UI'da `app.js:renderTimeline` içindeki `tl-error` bloğunda gösterilsin.

**Boyut:** küçük–orta. İlk katman (deterministik aday listesi) tek başına
değerli ve model gerektirmiyor — **önce bunu yap, model katmanını sonra tart.**

---

### İş 3 — Katalog tazelik sinyali ("cache stale" fikrinin bizdeki karşılığı)

**Neden:** e2e'nin cache'i uygulama değişince bunu *fark ediyor*. Bizim
kataloglarımız değişimi fark etmiyor — bayatladığını ancak bir koşu patlayınca
öğreniyoruz, hem de yanlış yeri suçlayarak.

**Ne kaydedilecek:** `findValueElement` bugün üç strateji deniyor
(fieldcontainer → `td`/`following-sibling::td` → `modularFormTable`). Hangi
stratejinin kazandığı, `(app, flow, label)` başına koşu dizinine yazılsın.
Bir sonraki koşuda strateji **değiştiyse** → ekran değişmiş demektir → rapora
uyarı: *"`Bayi Ünvanı` artık farklı bir yapıda çözülüyor;
`ngc/dbs-fatura-olustur.yaml` yeniden keşfedilmeli."*

Bunu **hız için değil, değişim tespiti için** yapıyoruz — Playwright
locator'ları zaten hızlı, cache'den kazanılacak süre yok. e2e'nin anahtarlama
şeması (test adı + hedef + talimat hash'i + parametreler + motor sürümü) iyi bir
şablon; bizde karşılığı `(app, flow, label, vocabulary.json sürümü)`.

**Nerede:** `PW/common/CommonActions.java` + `RunContext` (koşu dizini zaten
orada), çıktı `{runDir}/locator-strategy.json`. Orchestrator tarafında
`ArtifactService` zaten dosya sunuyor; yeni bir artifact tipi.

**Boyut:** orta. İş 2'den sonra yapılmalı — aday listesi altyapısı burada da
kullanılıyor.

---

## 5. Açıkça **almayacağımız** şeyler

| Fikir | Neden hayır |
|---|---|
| `agent.assert` — doğal dille doğrulama | Pass/fail modelin yargısına bağlanır. Denetlenemez, banka regresyonunda kabul edilemez. |
| Koşu anında `agent.act` ile adım geçirme | "Model yeni bir tıklama yolu icat edemez" kuralı projenin güven temeli. İş 2 bunun denetlenebilir versiyonu. |
| Cache'in kendisi (eylem izi JSON) | Bizde karşılığı `.feature` + sabit vocabulary. İkincisi daha iyi: okunabilir, doğrulanabilir, versiyonlanabilir. Yan yana ikisini taşımak saf karmaşa. |
| Mobil motor | Kapsamda yok. |
| Telemetri | Kurum içi, dışarı veri çıkmaz. |

---

## 6. Ölçüldü: npm VDI'da çalışıyor (2026-10-07)

**Artık bilinmeyen değil.** VDI'da ölçüldü:

| Komut | Sonuç |
|---|---|
| `node --version` | `v20.20.2` |
| `npm --version` | `10.8.2` |
| `npm config get registry` | `https://registry.npmjs.org/` — iç Artifactory DEĞİL, genel registry |
| `npm config get https-proxy` | `http://gmproxy.kfs.local:8080` |
| `npm ping` | PONG, 2715 ms (aynı komut ev makinesinde 341 ms) |
| `npm view react version` | `19.3.0` |
| `npm install react@18.3.1 react-dom@18.3.1` | **`added 5 packages ... in 7s`** |

Belirleyici olan son satır. Metadata okumak ile tarball indirmek ayrı
şeylerdir ve bir proxy ilkini geçirip ikincisini kesebilir — burada ikisi de
çalışıyor, yani **paket kurulumu VDI'da mümkün.**

Üç uyarı, sonucu küçültmek için değil doğru okumak için:

- Kurulum `%LOCALAPPDATA%` altına yapıldı. Repo ise **Z:'ye bağlı bir UNC ağ
  payında** duruyor ve `node_modules` on binlerce küçük dosyadır; H2 veritabanı
  dosyası tam bu nedenle yerel diske taşınmıştı (`application.properties`,
  "OUTSIDE" notu). Z: üzerinde `npm install` ayrıca ölçülmeli.
- Ölçüm genel registry'ye karşı yapıldı, iç Artifactory'ye karşı değil. Kurumsal
  politika registry'yi sonradan değiştirirse sonuç değişebilir.
- Bu, `e2e` çerçevesini almak için bir gerekçe **değil**: bölüm 3'teki beş
  gerekçe npm çalışsa da aynen duruyor. Ölçüm yalnızca "pilot kurup bakalım"
  seçeneğinin teknik olarak açık olduğunu söylüyor.

Ölçümü tekrarlayan script: `scripts/vdi-frontend-probe.ps1` (metadata, gerçek
tarball indirmesi ve düz HTTPS GET'i ayrı ayrı raporlar).

Dördü de geçerse **tek günlük, zaman kutulu** bir pilot anlamlı olabilir —
**ama yalnızca NGI login akışında.** NGC ödeme akışlarında deneme yapılmaz:
para hareketi var, `Onay` butonu var. Pilotun amacı çerçeveyi benimsemek değil,
tek bir soruyu ölçmek: *kendi vLLM'imizle, kendi uygulamamızda, cache gerçekten
replay ediyor mu?* Cevap bizim İş 1 ve İş 3'ün ne kadar değerli olduğunu söyler.

> Not: `qwen3-6` görü (vision) desteklemiyorsa e2e'nin yerleşik agent'ı zaten
> ayağa kalkmaz; bu durumda pilot `@e2e-dev/decision` ile denenmeli, ki o da
> ayrı bir decision-model sağlayıcısı istiyor. Pilot başlamadan bu iki şey
> netleşmeli.

---

## 7. Sıra ve doğrulama

| # | İş | Önkoşul | Nasıl doğrulanır |
|---|---|---|---|
| 0 | Bu dosya + `PROJECT.md`'den link | — | **tamam** |
| 1 | npm erişim kontrolü (4 komut) | VDI | **tamam** — bölüm 6, 2026-10-07 |
| 2 | **İş 2 / 1. katman** — deterministik aday listesi | — | `banko-payment.yaml`'da bir etiketi kasten boz → koşu patlar **ve** raporda 3 aday görünür |
| 3 | **İş 1** — decision model | — | NGC login'de iki `Giriş` linki olan sayfada agent tek seferde doğru olanı seçer; `BudgetedTool` sayacı tekrar tüketilmez |
| 4 | **İş 3** — locator stratejisi kaydı | İş 2 | DBS Fatura özet ekranında strateji değişimi yakalanır, uyarı artifact'a düşer |

**Her adım `CLAUDE.md`'deki yürütme modeline tabi:** kodu Claude yazar,
çalıştıran kullanıcıdır (VDI veya Docker yığını). Sonuç (ekran görüntüsü /
konsol çıktısı) getirilmeden hiçbiri "oldu" sayılmaz.

---

## 8. Tek paragraflık özet

e2e'nin en iyi fikri — AI'yı bir kez kullanıp sonucu deterministik hâle
dondurmak — bizde **zaten var**, üstelik daha denetlenebilir bir biçimde:
onların cache'i makine formatında bir eylem izi, bizimki analistin okuduğu
Gherkin. Çerçeveyi almak Faz 1–2'yi çöpe atıp Gherkin katmanını kaybetmek
demek; almıyoruz. Buna karşılık üç fikri kendimiz yazıyoruz: **(1)** agent'a
serbest metin yerine çoktan seçmeli sorma — bu, bugün açık duran "model aynı
tahmini onlarca kez tekrarladı" arızasını yapısal olarak kapatır; **(2)** bir
adım patladığında aday etiketleri rapora düşürme — healing değil, teşhis, çünkü
devralmanın testi *geçirmesi* projenin güven temelini çökertir; **(3)** locator
çözüm stratejisini kaydedip değiştiğinde "bu katalog bayatladı" diye uyarma.
Üçü de mevcut sınıflara eklenir, yeni bir yığın gerektirmez.

---

## Kaynaklar

- <https://tester.army/e2e>
- <https://github.com/tester-army/e2e>
- <https://tester.army/blog/introducing-e2e>
- <https://e2e.tester.army/docs/cache>
- <https://e2e.tester.army/docs/decision-models>
- <https://e2e.tester.army/docs/models>
