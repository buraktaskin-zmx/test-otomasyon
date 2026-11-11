# Dashboard — ne gösterilir, nereden gelir

Senaryum'un **Genel Bakış** sayfası, bir ekrana tıklanınca açılan **Ekran
detayı** ve bir senaryoya tıklanınca açılan **Senaryo geçmişi** için veri
sözleşmesi. Tasarım (Claude Design taslakları) görünümü belirler; bu belge
**hangi rakamın ne anlama geldiğini ve nereden hesaplandığını** belirler.

Kural: **verisi olmayan bir rakam gösterilmez.** Taslakta olup bugün
saklanmayan her şey aşağıda hangi aşamada geleceğiyle birlikte yazılı. O
aşamaya kadar kart "henüz ölçülmüyor" der, uydurma bir sayı göstermez.

---

## 1. Dashboard hangi soruları cevaplar

| Soru | Nerede |
|---|---|
| Bu dönemde ne koştu, ne geçti, ne düştü? | KPI kartları, günlük grafik |
| Düşenler **neden** düştü? | En çok takılan adımlar |
| Hangi ekran zayıf? | Ekran bazında senaryolar |
| Hangi senaryo hiç koşmadı, hangisi koşulamaz? | KPI + ekran detayı |
| Hangi senaryo bazen geçip bazen düşüyor? | Kararsız senaryolar |
| Hangi CIF ile koşuldu, hangi CIF'te düşüyor? | CIF bazında özet |

---

## 2. Tanımlar

Her kart bu tanımlardan birini kullanır. Bir rakam iki kartta iki farklı
anlama gelmesin diye burada bir kez yazılır.

| Terim | Tanım |
|---|---|
| **Dönem** | 24 saat · 7 gün · 30 gün. Koşular `TEST_RUN.submitted_at`'e göre döneme girer. "Önceki dönem" aynı uzunlukta, hemen öncesi; yanındaki `+12` / `-3` farkı ona göredir. |
| **Gün** | **Europe/Istanbul** saatine göre kesilir. `submitted_at` UTC bir `Instant` olarak saklanıyor; UTC'ye göre kesilseydi gece 00:00–03:00 arasındaki koşular önceki güne düşerdi. "Son 24 saat" ise şu andan geriye 24 saattir, gün sınırı kullanmaz. |
| **Biten koşu** | `status` ∈ {`DONE`, `FAILED`}. `CANCELED` (durduruldu) ve `UNKNOWN` (orchestrator yeniden başladı, sonucu bilinmiyor) sayılmaz; ayrı gösterilir. |
| **Senaryonun son durumu** | Dönemdeki **son biten** koşusunun sonucu: `DONE` → başarılı, `FAILED` → başarısız. |
| **Koşulabilir senaryo** | Gherkin adımı olan (`stepCount > 0`) ve Gherkin'i okunabilen (`problem` yok) senaryo. |
| **Koşulamaz senaryo** | Yalnızca doğal dil, Gherkin'i yok. "Hiç koşmadı"dan **ayrı** sayılır: onu koşturmak için önce Gherkin yazılmalı. |
| **Hiç koşmadı** | Koşulabilir ve dönemde **hiç biten koşusu yok**. |
| **Başarı oranı** | Son durumu başarılı senaryo / son durumu olan senaryo. **Senaryo** sayılır, koşu değil: on kez koşturulan bir senaryo oranı on kat etkilemesin. |
| **Koşu başarı oranı** | Başarılı biten koşu / biten koşu. İkincil gösterge, ekran detayında. |
| **Kararsız (flaky)** | Dönemde hem başarılı hem başarısız biten koşusu olan senaryo. |
| **Takılan adım** | Bir koşunun `FAILED` adımı (`TEST_RUN_STEP.status = FAILED`). Adım metnine göre gruplanır; login satırı gruplamaya girmez (metni redakte). |
| **Süre** | Koşunun adım sürelerinin toplamı (`TEST_RUN_STEP.duration_ms`). Ortalama yalnız biten koşulardan alınır. |

---

## 3. Veri envanteri

### Bugün saklananlar (orchestrator H2)

| Tablo | Alanlar | Kullanıldığı yer |
|---|---|---|
| `TEST_RUN` | id, submitted_at, finished_at, status, executor, scenario_name, app, flow, product, intent, feature (redakte), step_count, current_step_index, error_message | Her koşu kartı |
| `TEST_RUN_STEP` | run_id, step_index, keyword, step_text (redakte), status, duration_ms, error_message | Takılan adımlar, süre, adım sayısı |
| `SCENARIO` / `SCREEN` / `MAIN_MENU` / başlıklar | senaryo, ekran, ana menü, başlık, sıra, Gherkin, kural bağları, gözden geçirme damgası | Senaryo sayıları, başlık dağılımı, "bayat" |
| `products/**/rules.yaml` | kurallar, DOĞRULANDI/VARSAYIM | Kural sayısı, kapsanmayan kurallar |
| Diskte: `runs/{runId}/` | report.html, run.log, traces/*.zip | Rapor / Günlük / Trace bağlantıları |

### Bugün saklanmayanlar — taslağın dayandığı ama eksik olanlar

| # | Eksik | Sonucu | Aşama |
|---|---|---|---|
| E1 | **Koşu ↔ kayıtlı senaryo bağı.** `TEST_RUN` yalnızca senaryonun **adını** tutuyor. | "Hiç koşmadı", başlık bazında başarı ve senaryo geçmişi ad eşleşmesine kalır; ad değişince ya da aynı adda iki senaryo olunca yanlış sayar. | 2 |
| E2 | **CIF ve test kullanıcısı.** `Redactor` koşu metninden siliyor, ayrı bir sütun yok. | CIF bazında hiçbir şey gösterilemez. | 2 |
| E3 | **Koşunun nereden başladığı:** tekli (Çalıştır / Git) mi, çoklu kuyruk mu? | "Son kuyruklar", "kuyruk başarısı" gösterilemez. | 2 |
| E4 | **Kuyruk kimliği.** Aynı Çalıştır'a basılışla başlayan koşuları bağlayan bir şey yok. | Bir kuyruğun sonucu toplu gösterilemez. | 2 |
| E5 | **AI üretim metrikleri:** taslak sayısı, doğrulamayı geçen, onarım turu, token. **Token artık kalıcı** (11.11.2026): her AI çağrısı tur ve tool ayrıntısıyla `AI_SESSION` / `AI_TURN` / `AI_TOOL_CALL`'a yazılıyor ve ayrı **Token Takibi** panelinde gösteriliyor — `TOKEN-TAKIBI.md`. Taslak sayıları için `SCENARIO_DRAFT` hâlâ yazılmadı. | "AI üretim hattı" kartı. | 3 |

---

## 4. Sayfa 1 — Genel Bakış

Üstte dönem seçici (24 saat · 7 gün · 30 gün) ve Yenile. Aşama 2'den sonra
yanına **CIF filtresi** gelir (tümü / tek CIF).

### 4.1 KPI kartları

| Kart | Büyük sayı | Alt satır | Kaynak | Aşama |
|---|---|---|---|---|
| Toplam senaryo | Kütüphanedeki senaryo sayısı | `N ekran · M başlık` | `SCENARIO`, `SCREEN`, başlıklar | 1 |
| Koşulabilir | Gherkin'i olan senaryo | `K koşulamaz (yalnız doğal dil)` | `SCENARIO` (stepCount, problem) | 1 |
| Çalıştırılan | Dönemde biten koşusu olan senaryo | `%x · Y hiç koşmadı` | `TEST_RUN` + E1 | 1 yaklaşık, 2 kesin |
| Başarılı | Son durumu başarılı senaryo | `+n önceki döneme göre · ort. süre` | `TEST_RUN`, `TEST_RUN_STEP` | 1 yaklaşık, 2 kesin |
| Başarısız | Son durumu başarısız senaryo | `F farklı adımda` | `TEST_RUN_STEP` | 1 yaklaşık, 2 kesin |
| Başarı oranı | Tanımdaki oran | `önceki dönem %y` | yukarıdakiler | 1 yaklaşık, 2 kesin |

"Yaklaşık" = aşama 1'de koşu, senaryoya **ad + ürün + ekran** ile
eşleştirilir. Kart bunu küçük bir "≈" ile belli eder; aşama 2'de kalkar.

- **Toplam senaryo** ve **Koşulabilir** dönem seçiciden etkilenmez — kütüphanenin
  bugünkü hâlidir. Görsel olarak diğer dördünden ayrılır (dönem farkı
  taşımaz, aynı grupta durmaz).
- **Çalıştırılan**'ın yüzdesi koşulabilir senaryoların yüzdesidir, toplamın
  değil; alt satır bunu söyler: `koşulabilirin %76'sı · 36 hiç koşmadı`.
- **Ortalama süre** aşama 2'ye kadar yorumlanmamalı: aynı anda 4 koşu tek
  başına koşudan yavaş olabilir, ve tekli / kuyruk ayrımı (`trigger`) aşama
  2'de gelir. Önceki dönemle süre farkı o zamana kadar gösterilmez.

### 4.2 Günlük çalıştırmalar (grafik)

Dönemdeki her gün için yığılmış çubuk: **başarılı**, **başarısız**,
**durduruldu**, **bilinmiyor**. Kaynak: `TEST_RUN.submitted_at`, `status`.
Aşama 1. Çubuğa tıklayınca o günün koşuları listelenir.

Çubuk **koşu** sayar, senaryo değil — aynı senaryo üç kez koştuysa üç kez
sayılır; altında bu yazar. "Bilinmiyor" da çizilir: dışarıda bırakılırsa
grafiğin toplamı son çalıştırmalar listesiyle tutmaz.

### 4.3 Son çalıştırmalar (tablo)

Son 20 koşu, en yeni üstte. Kolonlar:

| Kolon | Kaynak | Aşama |
|---|---|---|
| Senaryo (ad + dosya yolu) | `scenario_name`; yol E1 ile | 1 / 2 |
| Ekran | `flow` → ekran adı | 1 |
| CIF | E2 | 2 |
| Tetik (tekli / kuyruk) | E3 | 2 |
| Durum | `status` (çalışıyor · başarılı · başarısız · durduruldu · bilinmiyor) | 1 |
| Adım | geçen / toplam (`TEST_RUN_STEP`) | 1 |
| Süre | adım süreleri toplamı | 1 |
| Ne zaman | `submitted_at` ("12 dk önce") | 1 |

Satıra tıklayınca → Senaryo geçmişi (§6). Rapor / Günlük bağlantıları
mevcut `artifactHref` ile.

### 4.4 En çok takılan adımlar

Dönemde en çok `FAILED` olan 5 adım: adım metni, kaç kez, kaç farklı
senaryoda, son hata mesajının ilk satırı. Kaynak: `TEST_RUN_STEP`. Aşama 1.

- Kart **koşu** sayar ("4× · 3 senaryo" = 4 koşuda, 3 farklı senaryoda);
  KPI'daki "N farklı adımda" ise **senaryo** sayar. İkisi toplanınca
  birbirini tutmaz ve tutmamalı — kartın altında "koşu sayısı" yazar.

- Taslaktaki açıklama satırı ("label ve değer aynı span sınıfında") **elle
  yazılmaz ve tahmin edilmez**; executor'ın hata mesajının ilk satırıdır.
  Executor mesajları zaten sebebi söyleyecek şekilde yazılı ("…etiketi ekranda
  ama açılır liste olarak açılamadı", "…görünür değil, sayfada 0 eşleşme").
- Login adımı listeye girmez; girişte düşen koşular ayrı bir satırda
  "Giriş başarısız: N koşu" olarak sayılır. Aynı CIF ile paralel oturum
  deneyinin sonucu burada görünür.

### 4.5 Ekran bazında senaryolar

Her ekran için yığılmış çubuk + sayılar: **başarılı · başarısız · hiç
koşmadı · koşulamaz**, sağda toplam. Ekrana tıklayınca → Ekran detayı (§5).
Kaynak: `SCREEN`, `SCENARIO`, `TEST_RUN`. Aşama 1 yaklaşık, 2 kesin.

Ekran sayıları kütüphanenin **bugünkü** hâlinden gelir:
- **Silinen** bir senaryonun koşuları geçmişte (son çalıştırmalar, takılan
  adımlar) kalır ama hiçbir ekranın sayısına girmez.
- **Başka ekrana taşınan** senaryonun aşama 1'deki koşuları eski ekranda
  sayılır, çünkü koşu kaydı `flow`'u taşınmaz. Aşama 2'de `scenario_id` ile
  senaryoyla birlikte taşınır.

### 4.6 Kararsız senaryolar

Dönemde hem geçen hem düşen senaryolar: ad, geçen/düşen sayısı, son düştüğü
adım. Kaynak: `TEST_RUN`. Aşama 2 (aşama 1'de ad eşleşmesi kararsızlığı
yanlış gösterebilir, o yüzden beklenir).

### 4.7 CIF bazında özet

| Kolon | Anlamı |
|---|---|
| CIF · test kullanıcısının adı | `TEST_RUN.cif`, `test_user_id` → `testdata/users.yaml` |
| Koşu | Dönemde bu CIF ile biten koşu |
| Başarılı / başarısız | Biten koşulardan |
| Girişte düşen | Login adımı `FAILED` olan koşu |
| Son koşu | En son ne zaman kullanıldı |

Aşama 2. Satıra tıklayınca dashboard o CIF'e filtrelenir.

Aşama 2'ye kadar kart **boş** durur: "CIF koşu kaydında henüz tutulmuyor."
Tasarımdaki örnek rakamlar ("10024571 · 48 koşu") gerçek arayüze girmez —
örnek bir sayı, okuyan için gerçek bir sayıdan ayırt edilemez.

### 4.8 AI üretim hattı

Taslak sayısı, doğrulamayı ilk seferde geçen, onarım turundan sonra geçen,
analistin elle düzelttiği, token toplamı. Aşama 3 (`SCENARIO_DRAFT` ve
kalıcı token sayacı). O zamana kadar kart tek satır: "AI üretim metrikleri
henüz kaydedilmiyor."

**Token kısmı ayrı panelde:** her AI çağrısının tur, token ve tool ayrıntısı
11.11.2026'dan beri kaydediliyor ve sol raydaki **Token Takibi** panelinde
gösteriliyor (`TOKEN-TAKIBI.md`). Bu kart taslak sayılarını bekliyor.

---

## 5. Sayfa 2 — Ekran detayı

Başlık: ekranın adı, ana menü, katalog yolu. Katalog dosyası yoksa
**"katalog yok"** yazar; yol, ekranın adından türetilip uydurulmaz (bugün
Nakit Yönetimi'nin dört ekranının hiçbirinin kataloğu yok). Aynı dönem seçici
ve CIF filtresi.

### 5.1 Ekran KPI'ları

Senaryo (`N kural` alt satırla, `rules.yaml`'dan) · Koşulabilir · Çalıştırılan
(`hiç koşmadı` alt satırla) · Başarılı · Başarısız (`F farklı adımda`) ·
Başarı oranı (`çalışanlar içinde`). Tanımlar §2'deki gibi, yalnızca bu ekrana
daraltılmış.

### 5.2 Başlık bazında dağılım

Her başlık için çubuk: başarılı · başarısız · hiç koşmadı · koşulamaz,
yanında senaryo sayısı. "Başlıksız" da bir satır. Kaynak: başlıklar +
`SCENARIO.heading_id` + `TEST_RUN`. **Aşama 2** (başlık senaryoya bağlı;
koşuyu senaryoya bağlamadan başlığa bağlanamaz).

### 5.3 Bu ekranda takılan adımlar

§4.4 ile aynı, yalnızca bu ekranın koşuları.

### 5.4 Senaryolar tablosu

| Kolon | Kaynak | Aşama |
|---|---|---|
| Senaryo (ad + dosya yolu) | `SCENARIO` | 1 |
| Başlık | `SCENARIO.heading_id` | 1 |
| Son durum ≈ | §2 | 1 yaklaşık, 2 kesin |
| Son 10 koşu | Nokta dizisi (§7'deki renklerle), en yeni sağda | 2 |
| Son koşu | Ne zaman, hangi CIF | 2 |
| Adım ≈ | Son koşuda geçen / toplam | 1 yaklaşık, 2 kesin |
| Süre ≈ | Son koşunun süresi | 1 yaklaşık, 2 kesin |
| Kurallar | Bağlı kural sayısı, "bayat" işareti | 1 |
| Ön koşul | `@risk:*` etiketleri (§8) | 1 |

Aşama 1'de **Son durum, Adım ve Süre** de ad eşleşmesiyle bulunur; KPI'lar
gibi kolon başlıklarında "≈" taşırlar.

**Kurallar** kolonu bugün Firma Fatura İşlemleri'nde **0** görünür: senaryolar
henüz `@R-FF-…` etiketiyle kurala bağlanmadı. Hangi senaryonun hangi kurala
ait olduğu `products/ngc/nakit-yonetimi/on-kosullar.md` tablosunda yazılı;
senaryolar etiketlendiğinde kolon dolar.

Filtreler: duruma göre (başarısız · hiç koşmadı · koşulamaz · kararsız),
başlığa göre. Satır seçimi Kayıtlı Senaryolar'daki gibi; seçilenler aynı alt
panelden **Çalıştır**'la kuyruğa girer (`hooks/useRunQueue.ts`).

**"Düşenleri tekrar çalıştır"** butonu "başarısız" filtresinin yanında durur:
son durumu başarısız olan, koşulabilir senaryoların hepsini tek tıkla aynı
kuyruğa koyar. Test verisi tarihe bağlı olduğu için en sık kullanılacak düğme
bu.

---

## 6. Sayfa 3 — Senaryo geçmişi

Senaryonun başlığı, ekranı, başlığı, bağlı kuralları. Altında dönemdeki tüm
koşuları, en yeni üstte:

| Kolon | Kaynak |
|---|---|
| Ne zaman | `submitted_at` |
| Durum | `status` |
| CIF | `TEST_RUN.cif` |
| Tetik / kuyruk | `trigger`, `batch_id` |
| Executor | `executor` |
| Süre, adım | `TEST_RUN_STEP` |
| Düşen adım ve hatası | `TEST_RUN_STEP` |
| Rapor · Günlük · Trace | `runs/{runId}/` |

Aşama 2 (koşuların senaryoya bağlanması gerekiyor).

---

## 7. Renk ve işaretler

**Çubuk ve noktalar mavi/kehribar, pill'ler yeşil/kırmızı** — ikisi ayrı, ve
bu ölçülmüş bir tercih (08.11.2026): bir dötorenop için yeşil↔kırmızı ayrımı
ΔE 6.4, mavi↔kehribar 22.2. Grafikte iki seri yan yana okunur, bir pill ise
tek başına ve yanında sözcüğüyle. Ayrıntısı `DESIGN.md` §2'de.

| Durum | İşaret (çubuk, nokta) | Metin |
|---|---|---|
| başarılı | `--primary` mavi | `--link` |
| başarısız | `--warn-mark` kehribar | `--warn` |
| durduruldu | `--skip` gri | `--skip` |
| bilinmiyor | `--skip` gri, kesik kenarlı | `--skip` |
| hiç koşmadı | `--chip-bg` açık gri | `--soft` |
| koşulamaz | `--chip-bg` üstüne çapraz tarama | `--soft` |

Renk tek başına anlam taşımaz: her çubuğun altında sayıyla birlikte sözcük
yazar ("48 başarılı · 6 başarısız"), ve segmentler 2px boşlukla ayrılır.

**Uzun listeler sayfalanır**, kart uzamaz: Son çalıştırmalar 8'er, takılan
adımlar 5'er, ekran detayının senaryo tablosu 12'şer satır. Tablolar kendi
kartının içinde kayar (sayfanın tamamı değil) ve başlık satırı sabit durur;
dar bir kartta sütunlar ezilmez, tablo yana kayar ve uzun metin kırpılır.

---

## 8. Ön koşul etiketleri

"Ön koşul" kolonunun makinenin okuyabileceği bir kaynağı olmalı;
`on-kosullar.md` serbest metindir, kod onu okuyamaz. Kaynak, senaryonun
**etiket satırı**:

| Etiket | Anlamı | Ekranda |
|---|---|---|
| `@risk:tarih` | Test verisi tarihe bağlı; belirli bir hafta/ay dışında düşer | tarihe bağlı veri |
| `@risk:yetki` | Seçilen test kullanıcısının bu işlem için yetkisi yok | yetki eksik |
| `@risk:veri` | Belirli bir kayıt (fatura, bayi) ortamda bulunmalı | veri gerekli |

- Etiketler `SCENARIO.other_tags`'ta zaten saklanıyor; yeni bir sütun gerekmez.
  Cucumber bilinmeyen etiketleri yok sayar, koşu etkilenmez.
- `on-kosullar.md` açıklamanın kendisi olarak kalır; etiket yalnızca "bu
  senaryoda dikkat edilecek bir şey var" işaretidir. Kolondaki sözcüğe
  tıklayınca belgenin o satırı açılır.

---

## 9. CIF

**Karar (07.11.2026):** CIF ve test kullanıcısı koşu geçmişinde **ayrı
sütunlarda** saklanır. Bu, 6 Ekim'deki "koşu geçmişinde CIF tutulmaz"
kararının yalnızca o kısmını değiştirir:

- Senaryo metninden ve adım metninden CIF'i silen `Redactor` **olduğu gibi
  kalır**; rapor ve geçmiş metinleri değişmez.
- Gerekçe: bunlar test ortamı kullanıcıları, gerçek müşteri değil; CIF
  bilinmeden "hangi kullanıcıyla düştü" sorusu cevaplanamıyor.
- Hem `cif` hem `test_user_id` tutulur: kullanıcı `users.yaml`'dan silinse de
  eski koşu hangi CIF'le koştuğunu bilir; adı ise kimlikten okunur.

Gösterildiği yerler: son çalıştırmalar (§4.3), CIF bazında özet (§4.7),
senaryo tablosundaki "son koşu" (§5.4), senaryo geçmişi (§6), her sayfada CIF
filtresi.

---

## 10. Veri modeli eklemeleri (aşama 2)

Hepsi `TEST_RUN`'a eklenen **boş bırakılabilir** sütunlar. `ddl-auto=update`
bunları açılışta ekler; mevcut satırlar boş kalır.

| Sütun | Tip | Dolduran | Not |
|---|---|---|---|
| `scenario_id` | UUID | Kayıtlı senaryodan başlayan her koşu | Editörden yazılıp kaydedilmeden koşulan senaryoda boş: "kayıtsız koşu" |
| `cif` | metin | `startRun` login'inden | |
| `test_user_id` | metin | Arayüzün seçtiği test kullanıcısı | |
| `trigger` | `single` / `queue` | Arayüz | AI ajan yürüyüşleri `TEST_RUN`'a yazılmıyor; ayrı kalır |
| `batch_id` | UUID | Çoklu kuyruk, Çalıştır'a her basışta bir tane | Tekli koşuda boş |

Bunun için `POST /api/v1/runs` gövdesine `scenarioId`, `testUserId`,
`trigger` ve `batchId` eklenir; hepsi isteğe bağlı, eski istemciler bozulmaz.

**Bilinmesi gereken:** aşama 2'den önceki koşuların bu sütunları boş
kalacak. CIF filtresi ve senaryo geçmişi onları göremez. Eski koşular yalnızca
ad eşleşmesiyle ve "≈" işaretiyle sayılır.

---

## 11. API önerisi

Hesaplar sunucuda yapılır; tarayıcı yüzlerce koşuyu çekip kendisi saymaz.

```
GET /api/v1/dashboard?period=7d&cif=
  → { period, previousPeriod, kpis{…}, daily[{date, passed, failed, canceled}],
      recentRuns[…], failingSteps[…], screens[{app, product, flow, name,
      passed, failed, neverRun, notRunnable}], flaky[…], cifs[…], ai: null }

GET /api/v1/dashboard/screens/{app}/{product}/{flow}?period=7d&cif=
  → { screen, kpis{…}, headings[…], failingSteps[…], scenarios[…] }

GET /api/v1/scenarios/{scenarioId}/runs?period=30d
  → [ { runId, submittedAt, status, cif, trigger, batchId, executor,
        durationMs, passed, total, failedStep } ]
```
---

## 12. Aşamalar

| Aşama | İçerik | Ön şart |
|---|---|---|
| **1** | Bugünkü veriyle: senaryo sayıları, koşulabilir/koşulamaz, günlük grafik, son koşular, takılan adımlar, ekran bazında dağılım ("≈" ile), `@risk:*` ön koşul kolonu, "Düşenleri tekrar çalıştır" | Yok |
| **2** | `TEST_RUN`'a `scenario_id`, `cif`, `test_user_id`, `run_trigger`, `batch_id`; arayüz bunları göndersin. Kesin başarı oranı, hiç koşmadı, başlık dağılımı, kararsızlar, CIF özeti, senaryo geçmişi, son 10 koşu noktaları | Aşama 1 |

**Durum (08.11.2026):** aşama 1 ve 2 kodlandı. Sütunun adı `trigger` değil
`run_trigger` (SQL anahtar sözcüğü). Arayüz senaryo kimliğini değil yolunu
(`scenarioPath`) gönderir; sunucu kimliğe çevirir. Açılışta
`RunScenarioBackfill`, kimliği olmayan eski koşuları bugünkü başlıklarıyla tek
bir senaryoya bağlayabiliyorsa bağlar; bağlanamayanlar "≈" kalır. Senaryo
geçmişi `GET /api/v1/dashboard/scenarios/{app}/{product}?path=` ile, son 30 gün.
| **3** | AI üretim hattı: `SCENARIO_DRAFT` ve kalıcı token sayacı | `SCENARIO_DRAFT` (PROJECT.md yol haritası 1) |

---

## 13. İstenmeyenler

- **Uydurma ya da tahmini rakam.** Taslaktaki "60 senaryo hiç koşmadı",
  "ortalama 1m 24s" gibi sayılar ancak tanımları (§2) ve verisi (§3) olunca
  gösterilir.
- **Koşu sayısıyla senaryo başarısını karıştırmak.** Bir senaryoyu on kez
  koşturmak başarı oranını on kat etkilememeli (§2).
- **"Hiç koşmadı" ile "koşulamaz"ı aynı gri çubukta toplamak.** Birincisi
  çalıştırılmayı bekliyor, ikincisinin önce Gherkin'e ihtiyacı var; yapılacak
  iş farklı.
- **Hata açıklamasını modelle üretmek.** Takılan adımın açıklaması
  executor'ın kendi mesajıdır.
- **Tasarımdaki örnek rakamları arayüze taşımak.** Verisi gelmemiş bir kart
  boş durur ve neden boş olduğunu söyler (§4.7, §4.8).
- **Durumları maviyle göstermek** (§7).

---

## 14. Açık sorular

1. **Dönemin varsayılanı** 7 gün mü olsun?
2. **Başarı oranında `CANCELED`**: durdurulan koşu "son durum" sayılmıyor
   (§2). Kuyrukta durdurulan bir senaryo, önceki biten koşusunun sonucunu
   taşımaya devam eder. Böyle kalsın mı?
3. **Ürün kapsamı**: dashboard bütün ürünleri mi birleştirsin, yoksa ana menü
   seçilsin mi? Bugün iki ürün var (Maaş Ödemeleri, Nakit Yönetimi).
4. **Kural kapsaması**: "hiçbir senaryonun bağlı olmadığı kurallar" ekran
   detayına konsun mu? Senaryolar bugün kurala bağlanmadığı için şu an hepsi
   "kapsanmıyor" görünür.
5. **Saklama süresi**: koşu geçmişi ve diskteki trace'ler hiç budanmıyor (30
   günlük dönem için sorun değil, disk için sorun).
6. **Executor durumu kartı** ("slot 3/4 dolu, içinde şunlar koşuyor"):
   şimdilik istenmedi (07.11.2026). İleride eklenirse kaynağı executor'ın
   `/cucumber/nodeStatus`'u olur, veritabanı değil.
