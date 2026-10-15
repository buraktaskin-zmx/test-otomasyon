# Çoklu Senaryo Üretimi — Baştan Sona Akış

Bir analist "bu ekranda DBS faturası oluşturmayı test etmek istiyorum" yazıp
**5** dediğinde ne oluyor? Bu doküman o cümleden çalışan teste kadar her adımı
anlatıyor: hangi HTTP isteği gidiyor, hangi AI çağrısı atılıyor, AI'nın elinde ne
var, neyin ne kadar sürdüğü, 5 senaryonun neden birbirinden farklı olduğu.

**Kimin için:** Bu projeye yeni bakan biri için. Cucumber, Gherkin, LLM, MCP —
hiçbirini bildiğiniz varsayılmıyor, her biri geçtiği yerde açıklanıyor. Uzun
olması kasıtlı; atlanan adım yok.

**İçindekiler**

1. [Önce dört kavram](#1-önce-dört-kavram)
2. [Üç ayrı "AI" var — karıştırmayın](#2-üç-ayrı-ai-var--karıştırmayın)
3. [Büyük resim](#3-büyük-resim)
4. [Faz 0 — Katalog nereden geliyor](#4-faz-0--katalog-nereden-geliyor)
5. [Faz 1 — Analist "Senaryoları Üret"e basıyor](#5-faz-1--analist-senaryoları-ürete-basıyor)
6. [Faz 2 — VariationPlanner: 5 kombinasyon nasıl bulunuyor](#6-faz-2--variationplanner-5-kombinasyon-nasıl-bulunuyor)
7. [Faz 3 — 5 model çağrısı](#7-faz-3--5-model-çağrısı)
8. [Faz 4 — Yanıt ve ekran](#8-faz-4--yanıt-ve-ekran)
9. [Faz 5 — Koşu](#9-faz-5--koşu)
10. [Süreler: ne hızlı, ne yavaş](#10-süreler-ne-hızlı-ne-yavaş)
11. [Üretilen her şey nerede durur](#11-üretilen-her-şey-nerede-durur)
12. [Bağlam ve "memory şişmesi" — üç net cevap](#12-bağlam-ve-memory-şişmesi--üç-net-cevap)
13. [Ne bozulabilir](#13-ne-bozulabilir)
14. [Sık sorulan sorular](#14-sık-sorulan-sorular)
15. [Doğrulanmamışlar ve bilinen eksikler](#15-doğrulanmamışlar-ve-bilinen-eksikler)

---

## 1. Önce dört kavram

Bu dördünü bilmeden gerisi okunmaz.

### 1.1 Gherkin / `.feature` dosyası

İngilizce gibi görünen, ama aslında makinenin çalıştırdığı bir test metni:

```gherkin
Feature: Online DBS Fatura Oluştur

  Scenario: Normal tipte fatura oluşturma
    Given login with cif "DI00002018" and userid "3014"
    And navigate to "NAKİT YÖNETİMİ" "Online DBS İşlemleri"
    And select row "FORD DBS ODEMELERI"
    And click "Fatura Oluştur"
    And select "93002" from "Bayi Kodu"
    And enter "Fatura No" "TEST1"
    And enter amount "Toplam Tutar" "1,00"
    And click "Devam"
    Then verify the "Toplam Tutar" has amount "1,00"
```

Her satır bir **adım**. `Given/And/Then` sadece okunurluk için; çalışma açısından
hepsi aynı. Önemli olan sonrası: `enter "Fatura No" "TEST1"`.

### 1.2 Adım sözlüğü (vocabulary) — kapalı bir gramer

Bir adım satırı, ancak projede **Java'da yazılmış** bir eşi varsa çalışır.
Bugün **17** desen var. Model bunların dışına çıkamaz: `enter` yazabilir,
`type` yazamaz. `type` yazsa doğrulayıcı reddeder.

Bu listeyi insan yazdı, model yazmıyor. Güvenin kaynağı bu: üretilen her test,
gerçek uygulamada daha önce koşmuş adımların üstünde duruyor.

> Liste `VocabularyExtractor` tarafından **derleme anında** Java anotasyonlarından
> üretiliyor (`vocabulary.json`), elle tutulmuyor. Bu yüzden kod ile sözlük asla
> ayrışamaz. Detay: [`VOCABULARY.md`](../VOCABULARY.md).

### 1.3 Katalog — ekranın sözlüğü

Bir ekranda hangi alanlar var, tipleri ne, açılır listede hangi seçenekler var,
ekrana nasıl gidiliyor. **Test verisi taşımaz.** Bir katalog, onlarca senaryoya
hizmet eder.

```yaml
app: NGC
flow: Online DBS Fatura Oluştur

navigation:
  main_menu: "NAKİT YÖNETİMİ"
  sub_menu: "Online DBS İşlemleri"
  entry_row: "FORD DBS ODEMELERI"
  entry_button: "Fatura Oluştur"

fields:
  - label: "Bayi Kodu"
    type: dropdown
    options: ["93002"]
    verifiable: true
  - label: "Bayi Ünvanı"
    type: text
    readonly: true          # uygulama kendisi dolduruyor
    verifiable: true
  ...
```

Formül:

```
senaryo  =  katalog (İSİMLER)  +  intent (FİİLLER ve DEĞERLER)  +  sözlük (GRAMER)
            ───────────────       ───────────────────────────      ──────────────
            Bayi Kodu diye         "1,00 TL'lik fatura             enter / select /
            bir alan var ve        oluşturmayı dene"               verify ...
            seçenekleri ["93002"]
```

Katalog bir **şablon değildir**. Şablon olsaydı tek bir senaryo üretirdi;
katalog olduğu için aynı dosyadan 5 farklı senaryo çıkabiliyor — ve doğrulayıcı
üretilen senaryoyu ona karşı **kontrol edebiliyor**.

Şema: [`catalogs/SCHEMA.md`](../catalogs/SCHEMA.md).

### 1.4 İki uygulama, iki süreç

```
┌──────────────────────────────┐        ┌──────────────────────────────┐
│  ORCHESTRATOR     :8035      │  HTTP  │  EXECUTOR         :9035      │
│                              │ ─────► │                              │
│  • Analistin gördüğü arayüz  │        │  • Cucumber + Playwright     │
│  • Doğrulama                 │        │  • GERÇEK tarayıcı açar      │
│  • LLM çağrıları             │        │  • Her an öldürülebilir      │
│  • Koşu geçmişi (H2)         │        │  • Veritabanı YOK            │
│  • MCP ajanı                 │        │                              │
│  • ASLA test koşmaz          │        │  • Aynı anda 1 koşu          │
└──────────────────────────────┘        └──────────────────────────────┘
```

Analist yalnızca `:8035`'e dokunur. 3 executor var, kuyruk yok: hepsi meşgulse
bir sonraki istek doğrudan reddedilir (503). Bu bilinçli — görünmez bir kuyrukta
beklemek, net bir ret'ten kötüdür.

---

## 2. Üç ayrı "AI" var — karıştırmayın

Bu, dokümandaki en önemli tablo. "Ajanın memory'si şişiyor mu?", "hepsi tek
çağrı mı?" sorularının cevabı **her biri için farklı**, ve çoğu zaman sorulan
şey aslında bunlardan sadece biri.

| | **A. MCP Ajanı** | **B. DOM Taraması** | **C. Senaryo Üretici** |
|---|---|---|---|
| Arayüzdeki sekme | **Ajan** | **Ekranı Keşfet** | **AI Üret** / **Çoklu Üret** |
| Uç nokta | `POST /api/v1/explore/catalog` | `POST /api/v1/discovery` | `POST /api/v1/scenarios/suggest-many` |
| Model kullanır mı | **Evet** | **HAYIR** — düz Java + Playwright | **Evet** |
| Kaç model turu | Onlarca (araç döngüsü) | Sıfır | **Senaryo başına 1** (+1 onarım) |
| Araç (tool) var mı | Evet, ~25 tarayıcı aracı + 3 bizim | — | **Hayır, hiç** |
| Gerçek tarayıcı sürer mi | **Evet** (orchestrator'da) | **Evet** (executor'da) | **Hayır** |
| Bağlam şişer mi | **EVET** — ölçüldü, çökmeye yol açtı | — | **HAYIR** — durumsuz |
| Tekrarlanabilir mi | **Hayır** | **Evet** | Büyük ölçüde (temperature 0.3) |
| Süre | Dakikalar (2-10) | Dakikalar (1-5) | Senaryo başına 10-30 sn |
| Ne üretir | Katalog taslağı (YAML) | Katalog taslağı (YAML) | N tane `.feature` metni |
| Ne sıklıkla koşar | Ekran başına **bir kez** | Ekran başına **bir kez** | **Her istekte** |

İki şeyi hemen netleştirelim:

- **A ve B "Faz 0"dır** — katalog üretir, ekran başına bir kez koşar. Analist
  bunları her senaryo için çalıştırmaz.
- **C "her gün kullanılan" yoldur** — ve içinde ne ajan var, ne tarayıcı, ne
  araç. Beş senaryo üretirken **hiçbir tarayıcı açılmaz** ve ajan hiç
  devreye girmez.

> **"5 senaryo tek bir ajan çağrısıyla mı üretiliyor?"**
> Hayır — ve soru iki yanlış varsayım taşıyor. (1) Üretim ajan değil, araçsız tek
> turlu bir model çağrısı. (2) Tek çağrı değil: **5 ayrı çağrı**, her biri kendi
> bağımsız konuşması. Nedenleri [§7.6](#76-neden-5-ayrı-çağrı-ve-5-ayrı-dosya)'da.

---

## 3. Büyük resim

```
╔════════════════════════════════════════════════════════════════════════════╗
║ FAZ 0 — KATALOG ÜRETİMİ            EKRAN BAŞINA BİR KEZ YAPILIR            ║
╚════════════════════════════════════════════════════════════════════════════╝

   ┌─ A. MCP AJANI ─────────────┐     ┌─ B. DOM TARAMASI ────────────┐
   │ [MODEL VAR]                │     │ [MODEL YOK]                  │
   │ LLM gerçek tarayıcı sürer  │     │ Java + Playwright DOM okur   │
   │ onlarca tur, dakikalar     │     │ deterministik, tekrarlanabilir│
   │                            │     │                              │
   │ İYİ OLDUĞU: yolu bulmak,   │     │ İYİ OLDUĞU: tipleri ÖLÇMEK,  │
   │ onay ekranı, başarı mesajı │     │ Select2 v3/v4, max_length    │
   │ KÖR OLDUĞU: tip tahmini    │     │ KÖR OLDUĞU: başarı mesajı    │
   └──────────┬─────────────────┘     └──────────┬───────────────────┘
              │                                   │
              └──────────────┬────────────────────┘
                             ▼
                  ┌──────────────────────┐
                  │  İNSAN İKİSİNİ OKUR  │  ◄── zorunlu adım
                  │  ve farkı karşılaştırır│
                  └──────────┬───────────┘
                             ▼
              catalogs/ngc/dbs-fatura-olustur.yaml
                             │
╔════════════════════════════│═══════════════════════════════════════════════╗
║ FAZ 1-3 — SENARYO ÜRETİMİ  │       HER İSTEKTE YAPILIR                     ║
╚════════════════════════════│═══════════════════════════════════════════════╝
                             ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ FAZ 1  Analist: ekran + CIF + sayı(5) + "ne test edilsin"     │
   │        POST /api/v1/scenarios/suggest-many                    │
   │        4 kapı: intent? sözlük? katalog? cif/userid tutarlı?   │
   └────────────────────────────┬──────────────────────────────────┘
                                ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ FAZ 2  VariationPlanner   [MODEL YOK — DÜZ JAVA]              │
   │        Katalogdan 5 farklı değer kombinasyonu HESAPLAR        │
   │        + tavan uyarıları                                      │
   └────────────────────────────┬──────────────────────────────────┘
                                ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ FAZ 3  5 KEZ, SIRAYLA:        [MODEL VAR, ARAÇ YOK]           │
   │        ┌─────────────────────────────────────────────┐        │
   │        │ model çağrısı → temizle → login'i zorla     │        │
   │        │        → DOĞRULA → geçersizse 1 onarım turu │        │
   │        └─────────────────────────────────────────────┘        │
   │        5 ayrı tek-senaryoluk .feature metni                   │
   └────────────────────────────┬──────────────────────────────────┘
                                ▼
╔════════════════════════════════════════════════════════════════════════════╗
║ FAZ 4 — EKRAN                                                              ║
╚════════════════════════════════════════════════════════════════════════════╝
        Kart listesi → birine tıkla → editörde tam açılır → "← Tümü"
                                │
╔═══════════════════════════════│════════════════════════════════════════════╗
║ FAZ 5 — KOŞU                  │   TASLAK BAŞINA, ANALİST TETİKLER          ║
╚═══════════════════════════════│════════════════════════════════════════════╝
                                ▼
        POST /api/v1/runs → doğrula → boş executor'a dağıt
                                ▼
        EXECUTOR: Cucumber + Playwright + gerçek tarayıcı
                                ▼
        Adım zaman çizelgesi · rapor.html · run.log · trace.zip
```

---

## 4. Faz 0 — Katalog nereden geliyor

Çoklu üretim **katalog olmadan çalışmaz**. Katalog yoksa `/suggest-many` 400 ile
döner ve eldeki katalogların listesini verir. O yüzden ilk iş katalog.

İki üretici var. İkisi de **taslak** üretir, ikisi de kendi başına yetmez.

### 4.1 Üretici A — MCP Ajanı (model var, tarayıcı sürer)

**Arayüzde:** "Ajan" sekmesi → "Katalog Üret" düğmesi.
**Uç nokta:** `POST /api/v1/explore/catalog`
**Kod:** [`orchestrator/explore/ExploreController.java`](../uptest-orchestrator/src/main/java/orchestrator/explore/ExploreController.java)

#### MCP nedir

**Model Context Protocol** — bir modele "araç" vermenin standart yolu. Burada
araçları veren şey `@playwright/mcp`: ayrı bir Node süreci, gerçek bir Edge
tarayıcı açıyor ve `browser_navigate`, `browser_click`, `browser_snapshot`,
`browser_type` gibi araçları sunuyor.

Tanım tek bir dosyada, `/.mcp.json`:

```json
{
  "mcpServers": {
    "playwright": {
      "command": "node",
      "args": [
        "C:/tools/playwright-mcp/node_modules/@playwright/mcp/cli.js",
        "--browser", "msedge",
        "--user-data-dir", "./.mcp-browser-profile",
        "--allowed-origins", "https://ifctest.yapikredi.com.tr;https://intest.yapikredi.com.tr",
        "--output-dir", "./runs/mcp"
      ]
    }
  }
}
```

> `--user-data-dir ./.mcp-browser-profile` → oturum çerezleri **diske** yazılıyor.
> Bu dizin canlı bir banka oturumu taşıyor; asla commit edilmemeli.
> `--allowed-origins` → ajan bu iki adres dışına çıkamaz.

Spring AI bu araçları kendiliğinden keşfediyor (`spring.ai.mcp.client.stdio
.servers-configuration=file:.mcp.json`). Araç listesi kodda **sayılmıyor** — MCP
sunucusuna bir araç eklenince Java tarafında değişiklik gerekmiyor.

#### Ajan döngüsü nasıl çalışıyor

Burası "tek çağrı mı?" sorusunun kalbi. **Tek HTTP isteği, onlarca model turu:**

```
   ANALİST                ORCHESTRATOR            MODEL (vLLM)         TARAYICI
      │                        │                       │                   │
      │ POST /explore/catalog  │                       │                   │
      ├───────────────────────►│                       │                   │
      │                        │ system + user prompt  │                   │
      │                        ├──────────────────────►│                   │
      │                        │                       │                   │
      │                        │  "browser_navigate(   │                   │
      │                        │   url=...) çağır"     │                   │
      │                        │◄──────────────────────┤                   │
      │                        │                       │                   │
      │                        │  aracı çalıştır ──────────────────────────►│
      │                        │◄──────────────────────────────── sonuç ───┤
      │                        │                       │                   │
      │                        │ [TÜM GEÇMİŞ] + sonuç  │  ◄── DİKKAT       │
      │                        ├──────────────────────►│                   │
      │                        │                       │                   │
      │                        │  "browser_snapshot()" │                   │
      │                        │◄──────────────────────┤                   │
      │                        │  ... ~50 TUR BÖYLE ...│                   │
      │                        │                       │                   │
      │                        │  "submit_catalog(...)"│                   │
      │                        │◄──────────────────────┤                   │
      │                        │  returnDirect → DÖNGÜ BİTER               │
      │                        │                       │                   │
      │◄───────────────────────┤ {catalog, problems}   │                   │
```

**Kritik satır:** her turda **önceki tüm geçmiş** yeniden modele gidiyor. Araç
sonuçları birikiyor. Sayfa snapshot'ı on binlerce karakter. Bu, [§12](#12-bağlam-ve-memory-şişmesi--üç-net-cevap)'de ayrıntılı
anlatılan şişmenin ta kendisi.

#### Ajanın elinde ne var

**1. Sistem promptu** — `ExploreController.SYSTEM`, ~115 satır. Her kuralı
ölçülmüş bir başarısızlıktan geliyor:

| Kural | Neden var |
|---|---|
| "Sayfa DEĞİŞTİĞİNDE snapshot al, her alan için değil" | 13 gereksiz okuma 2 dakikalık yürüyüşü 10 dakika yapıyordu |
| "Bir elementi ARIYORSAN, ARA — snapshot ÇEKME" | Bağlam şişmesinin ana azaltıcısı |
| "Uygulamanın formu değiştirmesi de bir değişikliktir" | Bağımlı alan yüklenince eski harita geçersiz |
| "Başarısız eylemden sonra sayfayı OKU" | Model aynı tahmini onlarca kez tekrarlıyordu |
| "Hiçbir şeyi değiştirmeyen tıklama hata DEĞİLDİR" | Sekme değişmedi, ajan yanlış ekranda devam etti |
| "TARİHLER YALNIZCA `set_date` ile" | Elle yazınca "20/26/2509" üretti |
| "TUTAR İKİ KUTUDUR" | "1.250,50" tek kutuya yazınca form boş okuyor |
| "AÇILIR LİSTELER GERÇEK `<select>` DEĞİL" | Seçenek seçme aracı hata veriyor; tıklayıp seçmek gerek |
| "TALİMAT, DOKUNABİLECEĞİN ŞEYLERİN TAM LİSTESİDİR" | Söylenmeyen bir kutuyu işaretleyince işlem değişiyor |
| "JavaScript YAZMA/ÇALIŞTIRMA" | Model tıklamayı bırakıp kod yazmaya başladı, kendi sentaks hatalarında döngüye girdi |

**2. Araçlar** — MCP'nin verdikleri, **iki tanesi hariç**:

```java
private static final Set<String> WITHHELD =
        Set.of("browser_run_code_unsafe", "browser_evaluate");
```

İkisi de JavaScript çalıştırıyor. Verildiğinde model tıklamayı bıraktı ve kod
yazmaya başladı; sonra kendi `SyntaxError`'larında döngüye girdi. Ayrıca
`browser_run_code_unsafe` kendi tanımına göre uzaktan kod çalıştırmaya eşdeğer —
bir bankanın içinde modele verilecek bir şey değil.

**3. Bizim yazdığımız 3 araç:**

| Araç | Ne yapar | Neden bizim |
|---|---|---|
| `note_field` | Bir alanı kaydeder (etiket, tip, seçenekler) | Alan başına **düz** bir çağrı; tek dev çağrı küçük modele fazla |
| `note_confirmation` | Onay ekranındaki etiketleri bir kez kaydeder | Soru, cevabın var olduğu yerde sorulmalı — bkz. aşağısı |
| `set_date` | Takvimi yıl→ay→gün sırasıyla sürer | Dört adımlık sırası taşıyıcı; 3 prompt kuralı yazıldı, 3'ü de bozuldu |

`note_confirmation`'ın hikayesi önemli: `verifiable` (bu alan onay ekranında
tekrar görünüyor mu?) başlangıçta `note_field`'ın bir argümanıydı ve **her koşuda
boş geldi**. Dikkatsizlik değil: bir alan form doldurulurken kaydediliyor, onay
ekranı ise henüz açılmamış. Dürüst cevap hep "hayır". Soru, cevabın var olduğu
ana taşındı.

**4. YAML'ı ajan yazmıyor.** "Sadece YAML yaz" denildiğinde model önce sayfalarca
gerekçe yazıyordu. Çözüm: katalog bir **araç çağrısı** oldu. Şekli şemanın
kendisi zorluyor, YAML'ı `CatalogSink.toYaml` — yani **bizim kodumuz** — render
ediyor. Modelin metni hiçbir yere sızamıyor.

**5. `submit_catalog` ve `returnDirect`:**

```java
@Tool(name = "submit_catalog", returnDirect = true, ...)
```

Bu olmadan araç modele "görev bitti, başka araç çağırma" **diyordu** ve model
devam ediyordu — akışı baştan yürüyordu. İstek hiç dönmeyince arayüzün düğmesi
sonsuza dek devre dışı kalıyordu. `returnDirect` sonucu doğrudan çağırana
veriyor, yani devam edilecek bir sonraki tur **yok**.

#### Ajanı bağlayan 7 ret

[`BudgetedTool`](../uptest-orchestrator/src/main/java/orchestrator/explore/BudgetedTool.java)
her araç çağrısını sarmalıyor. Sırası önemli:

```
   model araç çağırmak istiyor
             │
             ▼
   1. Tarayıcı kapatıldı mı?        → "CLOSED"  (yapışkan, geri dönüşsüz)
   2. Katalog gönderildi mi?        → "FINISHED"
   3. Yasaklı kontrol mü? (TIKLAMA:) → "FORBIDDEN"
   4. Talimatta geçmeyen alan mı?   → "UNMENTIONED"
   5. Zorunlu adım atlandı mı?      → "SKIPPED_STEP"  (yalnız kayıt araçları)
   6. İkinci navigasyon mu?         → "ALREADY_THERE"
   7. Bütçe bitti mi? (250)         → "EXHAUSTED"
             │
             ▼
        aracı çalıştır → sonucu 12.000 karaktere kırp
```

**Hepsi bir CÜMLE döndürüyor, exception atmıyor.** Sebep: hata atan bir araç,
modelin **tekrar denediği** araçtır. Cümle ise okunup anlaşılıyor.

İki sözleşme analistin metninden okunuyor:

```
TIKLAMA: Garantili Ödeme, Fatura numarası eklemek istiyorum
ZORUNLU: Ödenecekler
```

- `TIKLAMA:` → bu kontrollere dokunmak reddedilir. Var olma sebebi: modele iki
  kez açıkça söylendi, yine bastı.
- `ZORUNLU:` → bu kontrollere dokunulmuş olmalı. Zorlanmıyor, **raporlanıyor**:
  numaralı bir adımda "şu listeye geç" denildi, ajan geçmedi, hata vermedi, ve
  **yanlış listenin doğru satırını** kataloglayarak devam etti. Sonraki her adım
  başarılı oldu. Fark eden tek şey bir referans numarasını ekran görüntüsüyle
  karşılaştırmak oldu.

Ve bütçe:

```java
@Value("${uptest.explore.tool-budget:250}") int budget
```

250 — gerçek bir akış ~50 çağrı: login ~6, menü ~4, 13 alan (her biri okunup
doldurulur) ~26, 3 submit + snapshot ~8. 250'ye ulaşmak "raydan çıkmış" demek,
"sayıyı artır" demek değil.

#### Ne üretiyor

```json
{
  "catalog": "app: NGC\nflow: ...\nnavigation:\n  main_menu: ...",
  "problems": [
    "\"Döviz Cinsi\" dropdown ama hic secenek yazilmamis; senaryo neyi secegini bilemez.",
    "Alanlar verifiable ama success_message yok. Ajan onay ekranini okudu ve orada durdu..."
  ]
}
```

`problems` makine kontrolü — kimse okumadan önce mekanik olarak bulunabilenler:
hiçbir adımın süremediği bir tip, seçeneksiz bir açılır liste, eksik
`main_menu`, `entry_button` var ama `entry_row` yok, `success_message` var ama
hiçbir alan `verifiable` değil (ve tersi).

### 4.2 Üretici B — DOM Taraması (model YOK)

**Arayüzde:** "Ekranı Keşfet"
**Uç nokta:** `POST /api/v1/discovery`
**Kod:** orchestrator tarafı [`CatalogDiscoveryService`](../uptest-orchestrator/src/main/java/orchestrator/catalog/CatalogDiscoveryService.java),
executor tarafı [`PW/discovery/CatalogDiscovery`](../uptest-executor/src/main/java/com/ykb/uplift/uptest/playwright/discovery/CatalogDiscovery.java)

Burada **hiç model yok**. Orchestrator, analistin formundan **sentetik bir
Gherkin senaryosu** yazıyor ve onu sıradan bir koşu gibi executor'a gönderiyor:

```gherkin
Feature: Katalog kesfi

  Scenario: Ekrani kesfet
    Given login with cif "DI00002018" and userid "3014"
    And navigate to "NAKİT YÖNETİMİ" "Online DBS İşlemleri"
    And select row "FORD DBS ODEMELERI"
    And click "Fatura Oluştur"
    Then discover screen catalog
    And fill discovered fields:
      | Bayi Kodu    | 93002 |
      | Toplam Tutar | 1,00  |
    And click "Devam"
    Then discover screen catalog
```

Bu iki adım (`discover screen catalog`, `fill discovered fields:`) **sözlükte
yayınlanmıyor** — `VocabularyExtractor` `discovery` paketini bilerek dışarıda
bırakıyor. Bir ekranı okurlar, bir testi tanımlamazlar; üretecin bunları asla
yazmaması gerekiyor. Bu yüzden elle `@Given/@When/@Then` sayınca **19**,
`GET /api/v1/vocabulary` deyince **17** çıkıyor.

Executor DOM'u geziyor, `catalog-draft.yaml` yazıyor, orchestrator onu artefakt
olarak çekip başına `app`/`flow`/`navigation` bloğunu ekliyor (executor hangi
uygulamaya giriş yaptığını bilmiyor — yalnızca üstünde durduğu ekranı görüyor).

**Yalnızca bu tarama görebildikleri:**

| Ne | Neden yalnızca o |
|---|---|
| Select2 **v4** mü **v3** mü | Ekranda **birebir aynı** görünüyorlar; hiçbir ekran görüntüsü ayırmıyor. Ayıran şey DOM: v4'ün kapalı widget'ı `.select2-selection` taşıyor. Yalnızca v4'ü süren bir adım var; v3 `UNSUPPORTED_V3` olarak işaretlenip tahmine kurban edilmiyor |
| Bir tutarın **iki input** olduğu | `input[id$='-integer']` / `-decimal` |
| `max_length`, `min`, `max`, `numeric` | Bunlar DOM **attribute**'ları. Erişilebilirlik ağacında hiç yok, bu yüzden ajan göremiyor |
| `readonly` | Ekran durumunu doldurma ÖNCESİ/SONRASI diff'leyerek **ölçülüyor** |
| Bağımlılıklar | "Bayi Kodu seçilince Bayi Ünvanı doldu, Döviz Cinsi'nin seçenekleri yüklendi" |

**Kör olduğu yerler:** `fieldcontainer` dışındaki hiçbir şey ("Ek Bilgi"yi kaçırdı),
ve `success_message` — son düğmeye hiç basmadığı için sonuç ekranını görmüyor.

### 4.3 Neden ikisi birden

Kör noktaları **zıt**, ve her biri diğerini yanlışlarken yakaladı:

```
                  AJAN                      TARAMA
   yolu bulmak      ✓ iyi                     ✗ analist yazmalı
   onay ekranı      ✓ okur                    ✗ ulaşamaz
   başarı mesajı    ✓ okur                    ✗ ulaşamaz
   tip (v3/v4)      ✗ göremez                 ✓ ÖLÇER
   iki kutulu tutar ✗ tahmin                  ✓ ÖLÇER
   max_length       ✗ göremez                 ✓ OKUR
   tekrarlanabilir  ✗ aynı ekran "Ay" / "Ay, Hafta, Gün" verdi   ✓ her seferinde aynı
```

**İnsan okumadan bir taslak katalog değildir.** Yeni bir ekranda ikisini de
koşun ve farkı okuyun: "Ek Bilgi" ve kaçırılmış bir `verifiable` böyle bulundu.

### 4.4 Kaydetme

Analist taslağı okuyup gerekirse düzeltir, sonra:

```
POST /api/v1/scenarios/catalogs
{ "app": "ngc", "flow": "dbs-fatura-olustur", "yaml": "app: NGC\n..." }
         ↓
catalogs/ngc/dbs-fatura-olustur.yaml
```

Kasten **doğrulanmıyor** (isim dışında): ajanın kendi ucu taslaktaki sorunları
zaten raporladı ve analist okuyup muhtemelen düzenledi. Burada kusurlu bir
kataloğu reddetmek, saklamaya değer bir taslağın tek yolunu "elle dosyaya
yapıştırmak" yapardı — ki bu zaten değiştirilmek istenen şey.

Kaydedilir kaydedilmez katalog "AI Üret" ve "Çoklu Üret" listelerinde görünür.

---

## 5. Faz 1 — Analist "Senaryoları Üret"e basıyor

Artık asıl akıştayız. Faz 0 bitti, elimizde bir katalog var.

### 5.1 Ekranda ne var

"Çoklu Üret" sekmesi:

```
┌─ Çoklu Üret ─────────────────────────────┐
│ Ekran                                    │
│ [ ngc/dbs-fatura-olustur            ▼ ]  │
│ CIF / Müşteri Kodu                       │
│ [ DI00002018                          ]  │
│ Kullanıcı ID                             │
│ [ 3014                                ]  │
│ Senaryo sayısı                           │
│ [ 5                                   ]  │
│ Ne test edilsin?                         │
│ ┌──────────────────────────────────────┐ │
│ │ Bu ekranda fatura oluşturmayı test   │ │
│ │ etmek istiyorum                      │ │
│ └──────────────────────────────────────┘ │
│        [ Senaryoları Üret ]              │
└──────────────────────────────────────────┘
```

Dikkat: analist **hiçbir değer yazmıyor**. Ne fatura numarası, ne tutar, ne
tarih. Değerler Faz 2'de hesaplanacak.

### 5.2 Tarayıcının gönderdiği tam istek

```http
POST /api/v1/scenarios/suggest-many HTTP/1.1
Content-Type: application/json; charset=utf-8

{
  "app":    "ngc",
  "flow":   "dbs-fatura-olustur",
  "intent": "Bu ekranda fatura oluşturmayı test etmek istiyorum",
  "cif":    "DI00002018",
  "userid": "3014",
  "count":  5
}
```

Tarayıcı tarafında bir ön kontrol var: NGC seçiliyse ve CIF yazılmışsa ama
Kullanıcı ID boşsa, istek **hiç gönderilmiyor** — aksi halde model çağrıldıktan
sonra 400 alırdınız.

### 5.3 Dört kapı (`prepare()`)

Model çağrılmadan önce dört şey kontrol ediliyor. Sıra, bir başarısızlığı en
ucuz açıklayan sıra:

```
┌──────────────────────────────────────────────────────────────────────┐
│ 1. intent boş mu?                                                    │
│    → 400  {"error": "intent is required"}                            │
├──────────────────────────────────────────────────────────────────────┤
│ 2. Adım sözlüğü var mı?   (VocabularyService.get())                  │
│    → 503  "No executor reachable, so the step vocabulary is unknown" │
│    REDDETMEK, TAHMİN ETMEKTEN İYİ: bayatlamış bir listeyle çalışan   │
│    bir doğrulayıcı, executor'ın "tanımsız adım" diye reddedeceği     │
│    senaryoları onaylar.                                              │
├──────────────────────────────────────────────────────────────────────┤
│ 3. Katalog diskte var mı? (catalogs.dir altında app/flow.yaml)       │
│    → 400  {"error": "No catalog for ngc/dbs-fatura-olustur",         │
│            "available": ["ngc/banko-payment", ...]}                  │
├──────────────────────────────────────────────────────────────────────┤
│ 4. cif verildi ama NGC'nin istediği userid yok mu?                   │
│    → 400  {"error": "userid is required for ngc"}                    │
└──────────────────────────────────────────────────────────────────────┘
```

Bu dört kapı `/suggest` ile **paylaşılıyor**, kopyalanmış değil — iki kopya ilk
değişiklikte ayrışır.

Çoklu üretimin bir **beşinci** kapısı var ve o paylaşılmıyor: cif boşsa
`/suggest-many` reddediyor (400, "Çoklu üretim için CIF zorunlu"). Sebebi
§5.5.1'de — tek senaryoda boş cif zararsız, batch'te N senaryoda N farklı
müşteri numarası demek. `prepare()` içine konmadı, çünkü `/suggest` boş cif'i
meşru olarak kabul ediyor.

Ayrıca `count < 1` ise 400. `count > 10` ise reddedilmiyor: 10'a kısılıyor ve
yanıtta bir **not** olarak söyleniyor.

### 5.4 Sözlük nereden geliyor

```
VocabularyService.get()
      │
      ├─ bellekte önbellek var mı? ──► var ──► döndür (HTTP yok)
      │
      └─ yok ──► her executor'a sırayla:  GET /cucumber/vocabulary
                      │
                      ├─ ilk dolu cevap kazanır → önbelleğe al → döndür
                      └─ hiçbiri cevap vermedi → Optional.empty() → 503
```

Sözlük **derleme anında** Java anotasyonlarından üretiliyor ve executor'ın
jar'ından servis ediliyor. Modüller arasında elle kopyalanmıyor. Bu bir HTTP
çağrısına mal oluyor ve karşılığında şunu garanti ediyor: doğrulayıcı
**gerçekten dağıtılmış** adımları tarif ediyor.

### 5.5 Login satırını model yazmıyor

```java
String loginStep = LoginStep.forApp(app, cif, userid);
// NGC → Given login with cif "DI00002018" and userid "3014"
// NGI → Given login to ngi with cif "DI00002018"
```

Neden orchestrator yazıyor: NGC ve NGI **farklı uygulamalar**, farklı login
adımları. Seçimi modele bırakmak yazı-tura oldu — userid içermeyen bir intent
verildiğinde aynı istek bir koşuda uydurma userid'li NGC adımını, sonraki koşuda
**tamamen yanlış uygulamaya giren** NGI adımını üretti.

Kataloğun `app` anahtarı cevabı zaten biliyor. **Bilinen bir gerçek asla bir soru
olmamalı.**

Dahası: bu satır modele sadece *rica* edilmiyor. Model ne yazarsa yazsın,
`LoginStep.apply()` üretilen taslaktaki her login satırını **siliyor** ve
doğrusunu senaryo başlığının altına yerleştiriyor. Prompt kuralı rica eder;
bu, garanti eder.

#### 5.5.1 Çoklu üretimde CIF ZORUNLU — tek senaryoda değil

Garanti, ancak **zorlanacak bir satır varsa** var. `LoginStep.forApp` boş bir
cif'e `null` dönüyor, `LoginStep.apply` de o durumda taslağa hiç dokunmuyor —
yani login satırını model yazmış olarak kalıyor.

Tek senaryoda bu kabul edilebilir: ortada tek bir login satırı var ve analist
tam ona bakıyor. **Batch'te değil**, çünkü her senaryo *ayrı bir model
çağrısı*. Ölçüldü: aynı istekten gelen beş taslakta beş farklı cif çıktı,
birinde NGC ekranında **NGI adımı**, birinde login satırı **hiç yok**:

```
cif DOLU  -> 5 taslak, hepsinde:  Given login with cif "70012345" and userid "ADMIN01"
cif BOS   -> Given login with cif "11111" and userid "22222"
             Given login with cif "99999" and userid "88888"
             Given login to ngi with cif "12345"          <-- yanlis uygulama
             (login YOK)
             Given login with cif "00000" and userid "1"
```

Analist "aynı kullanıcı, bu ekranı beş farklı veriyle" istedi; beş kullanıcı ve
beş elle düzeltme alırdı. Bu yüzden `/suggest-many` boş cif'i **reddediyor**,
`/suggest` kabul ediyor.

Hangi yarısının zaten çözülmüş olduğuna dikkat: `VariationPlanner`'ın
değiştirdiği değerler **katalog alanları**, ve katalog tasarım gereği hiçbir
kimlik bilgisi taşımıyor (`catalogs/SCHEMA.md`). Yani login'i değiştiren bir şey
hiç yoktu — sapma, onu **zorlamamaktan** geliyordu. "Sabit tutuluyor" ile "hiç
değiştirilmiyor" aynı garanti değil; sadece ilki zorlanmış bir değer istiyor.

---

## 6. Faz 2 — VariationPlanner: 5 kombinasyon nasıl bulunuyor

**Bu bölüm dokümanın kalbi.** "Değerler nasıl bulunuyor?" ve "5 şablon nasıl
üretiliyor?" sorularının cevabı burada.

**Kod:** [`orchestrator/llm/VariationPlanner.java`](../uptest-orchestrator/src/main/java/orchestrator/llm/VariationPlanner.java)
**Model kullanmıyor.** Düz Java. Deterministik.

### 6.1 Neden modelden istenmiyor

En doğal fikir şu olurdu: modele "beş farklı senaryo yaz" demek. Çalışmıyor, ve
sebebi `application.properties`'te yazılı:

```properties
# 0.0 = deterministic, 1.0 = creative. Gherkin generation wants the low end:
# the same request must produce the same scenario, not a new invention.
spring.ai.openai.chat.options.temperature=0.3
```

0.3 kapalı bir sözlüğe karşı Gherkin yazdırmak için **doğru** ayar. Aynı zamanda
şu demek: aynı promptu 5 kez gönderirseniz **5 neredeyse-aynı dosya** alırsınız.

Prompt seviyesinde tek çözüm sıcaklığı yükseltmek — ki çeşitliliği
**uydurma etiketler ve yeniden kelimelenmiş adımlar** satın alarak elde eder.
Doğrulayıcının zamanını harcadığı şey tam olarak budur.

Bu projenin tekrar eden dersi: **bir prompt kuralı ancak kod olunca tutar.**
O yüzden farklar Java'da hesaplanıyor ve modele **harfi harfine** veriliyor.

Hesaplamanın üç yan faydası var, promptun veremeyeceği:

1. Seçilen her değer kataloğun **kendi seçenek listesinden** geliyor, yani
   doğrulamadan geçeceği baştan biliniyor.
2. `shown_when` **doğru** çözülüyor, umutla değil.
3. `readonly` alanlara **hiç** değer atanmıyor.

### 6.2 Algoritma

```
plan(katalog, 5):

  for i = 0 … 4:                         ← her senaryo
      değerler = {}
      işaretlenecek = []
      dokunulmaz = []

      for alan in katalog.fields():      ← KATALOG SIRASI = EKRAN SIRASI
                                            tek geçiş, bu taşıyıcı

          if alan.readonly:
              ATLA                        ← uygulama dolduruyor

          if alan.shown_when:
              ebeveyn = değerler[alan.shown_when.field]
              if ebeveyn == null:        ← ebeveyn SONRA geliyor / readonly
                   dokunulmaz += alan ; NOT DÜŞ ; devam
              if ebeveyn != alan.shown_when.value:
                   dokunulmaz += alan ; devam

          if alan.type == checkbox:
              if i çift: işaretlenecek += alan
              else:      dokunulmaz   += alan
              devam

          değerler[alan] = tipine göre merdiven(alan, i)

      varyasyonlar += {i, etiket, değerler, işaretlenecek, dokunulmaz}

  tavan_uyarısı()
  kısıt_uyarısı()
```

Sıranın taşıyıcı olmasının sebebi: `shown_when` ebeveyni **daha önce** atanmış
olmalı. Bu ekranlarda ebeveyn gerçekten önce geliyor (`Fatura Tipi` 14., `E-Fatura
Hash Kodu` 15.). Gelmezse tahmin edilmiyor — **not düşülüyor**.

### 6.3 Tip tip kurallar

Her alan bir **merdivenden** okuyor. Hangi basamağı okuduğu, alanın türüne göre
iki ayrı şekilde hesaplanıyor — ve bu ayrım [§6.3.1](#631-neden-iki-ayrı-indeks)'de
anlatıldığı gibi taşıyıcı:

| `type` | Merdiven | Basamak nasıl seçilir |
|---|---|---|
| `dropdown` | katalogdaki `options` | **kombinasyon sayacı** (aşağı) |
| `account_picker` | katalogdaki `accounts` | **kombinasyon sayacı** |
| `checkbox` | işaretli / dokunulmaz | **kombinasyon sayacı** |
| `date` | `bugün` / `+1 gün` / `+3 gün` / `+1 hafta` / `+1 ay` — **göreli ifade**, koşu anında çözülür | senaryo no (kaydırma YOK) |
| `amount` | `max_length` varsa ondan türetilen sınır merdiveni; yoksa `1,00 / 0,01 / 250,00 / 1.250,55 / 9.999,99` | senaryo no **+ alan sırası** |
| `text` + `numeric` + `max_length` | `min`/`max` aralığında basamak merdiveni, kutuya sığdırılmış | senaryo no **+ alan sırası** |
| `text` + `max_length` | uzunluk merdiveni: `min(5,limit)` / `limit` / `limit/2` / `1` / `min(2,limit)` karakter, dolgu `"TESTVERISI"` tekrarı | senaryo no **+ alan sırası** |
| `text` (kısıt yok) | `TEST1 / TEST22 / TST3 / TESTVERISI4 / T5` **+ uyarı notu** | senaryo no **+ alan sırası** |
| `readonly: true` | — | **hiçbir şey** — atanmaz |
| diğer / `UNSUPPORTED_V3` | — | atlanır, onu süren bir adım yok |

#### 6.3.1 Neden iki ayrı indeks

Başta her alan sadece **senaryo numarasını** kullanıyordu (`i % k`). İki ayrı
şekilde yanlıştı:

**Seçim alanları köşegen yürüyordu.** İki tane iki-seçenekli açılır liste
düşünün. İkisi de `i % 2` kullanırsa:

```
         liste A      liste B          TEST EDILEN       EDILMEYEN
  S1      Normal       TL              (Normal, TL)
  S2      E-Fatura     USD             (E-Fatura, USD)   (Normal, USD)
  S3      Normal       TL              — tekrar —        (E-Fatura, TL)
  S4      E-Fatura     USD             — tekrar —
```

Dört kombinasyonun **ikisi hiç test edilmiyor**, ve S3/S4 sadece tekrar. Artık
seçim alanları tek bir **karışık tabanlı sayacın basamakları**: her alanın bir
"basamak değeri" var, ve senaryolar kombinasyonları dolaşıyor.

```
  S1  (Normal, TL)     S2  (E-Fatura, TL)
  S3  (Normal, USD)    S4  (E-Fatura, USD)     ← dördü de kapsandı
```

**Veri alanları aynı değeri paylaşıyordu.** DBS formunda altı metin kutusunun
hepsi 1. senaryoda `"TEST1"` alıyordu — iki fatura numarası **birebir aynı**
oluyordu, ki bir tekillik kontrolü bunu reddeder. Artık her veri alanı
merdivende kendi sırası kadar kaydırılıyor, yani bir senaryonun içinde farklı
değerler alıyorlar.

**Tarihler bu kaydırmanın DIŞINDA — tek istisna.** Tarihleri de kaydırmak
denendi ve zarar verdi: `Fatura Tarihi`, `Başlangıç Vadesi`'nden altı ay sonraya
düştü. Kimsenin istemediği bir sıralama, ve uygulama bunu reddedebilir. "Şu
tarih şundan önce olmalı" gibi alan ilişkileri katalogda yazılı değil ve tahmin
edilemez. O yüzden bir senaryodaki **tüm tarihler aynı günü** alıyor: kendi içinde
tutarlı, ve senaryodan senaryoya hâlâ değişiyor.

Üç tasarım kararı:

- **Checkbox'a değer atanmaz.** `check {string}` adımı değer almıyor, sadece
  etiket. İki durumu var: "çağrıldı" ve "çağrılmadı". Çağrılmayan yarısı
  `dokunulmaz`a giriyor çünkü **bir kutuya iki kez tıklamak işareti kaldırır**.
- **`readonly` hiç atanmaz.** Devre dışı bir input isteğe bağlı değil, yazılabilir
  değil. `enter` koymak 30 saniye boyunca asla yazı kabul etmeyecek bir elementi
  beklemek demek. Gerçek vaka: `Bayi Ünvanı`, seçilen `Bayi Kodu`'ndan
  uygulamanın kendisi dolduruyor.
- **Metin değerlerinde boşluk, tire, noktalama yok.** `"TEST VERISI 4"` ve
  `"TEST-2"` çıkarıldı. Düzen kaygısı değil: bu değerler kataloğun tarif
  edemediği bir metin kutusuna gidiyor, ve bu ekranlarda o kutu **maskeli**
  olabilir. Maske boşluğu da tireyi de reddeder; `"TESTVERISI4"`'ün en azından
  bir şansı var. Uzunluk hâlâ değişiyor, çünkü tarif edilmemiş bir kutuda tek
  ölçülebilir eksen uzunluktur.

### 6.4 Gerçek katalogla yürütme

`catalogs/ngc/dbs-fatura-olustur.yaml`, 15 alan. Planlayıcı 5 senaryo için şunu
üretiyor. Tarihler **göreli ifade** olarak duruyor — senaryo metnine `+3 gün`
yazılıyor ve gerçek gün tarayıcı koşarken hesaplanıyor, yani senaryo hiç
bayatlamıyor:

| # | Alan | type | S1 | S2 | S3 | S4 | S5 |
|---|---|---|---|---|---|---|---|
| 1 | Bayi Kodu | dropdown (1) | 93002 | 93002 | 93002 | 93002 | 93002 |
| 2 | **Bayi Ünvanı** | readonly | — | — | — | — | — |
| 3 | Başlangıç Vadesi | date | `bugün` | `+1 gün` | `+3 gün` | `+1 hafta` | `+1 ay` |
| 4 | Fatura No | text | TEST22 | TST3 | TESTVERISI4 | T5 | TEST1 |
| 5 | Orijinal Fatura Numarası | text | TST3 | TESTVERISI4 | T5 | TEST1 | TEST22 |
| 6 | Döviz Cinsi | dropdown (1) | TL | TL | TL | TL | TL |
| 7 | **Taksit Adedi** | text | **TESTVERISI4** | **T5** | **TEST1** | **TEST22** | **TST3** |
| 8 | Toplam Tutar | amount | 9.999,99 | 1,00 | 0,01 | 250,00 | 1.250,55 |
| 9 | Orijinal Fatura Tutarı | amount | 1,00 | 0,01 | 250,00 | 1.250,55 | 9.999,99 |
| 10 | Taksit Periyot Birimi | dropdown (1) | Ay | Ay | Ay | Ay | Ay |
| 11 | Taksit Periyodu | text | TEST22 | TST3 | TESTVERISI4 | T5 | TEST1 |
| 12 | Fatura Dönemi | text | TST3 | TESTVERISI4 | T5 | TEST1 | TEST22 |
| 13 | Fatura Tarihi | date | `bugün` | `+1 gün` | `+3 gün` | `+1 hafta` | `+1 ay` |
| 14 | **Fatura Tipi** | dropdown (2) | **Normal** | **E-Fatura** | **Normal** | **E-Fatura** | **Normal** |
| 15 | E-Fatura Hash Kodu | `shown_when` | ⊘ | TEST1 | ⊘ | TST3 | ⊘ |

`⊘` = `dokunulmaz` listesinde; o senaryo bu alanın adını **hiçbir adımda
geçirmeyecek**.

**`shown_when`'in koda geçtiğinin kanıtı 15. satır:** `Fatura Tipi = E-Fatura`
seçen S2 ve S4 hash kodunu dolduruyor, `Normal` seçen S1/S3/S5 ona hiç
dokunmuyor. Bugün bu karar modele bırakılsa, modelin yanlış tarafa düştüğü
ekranda 30 saniyelik zaman aşımı oluyor.

### 6.5 Bu katalog için basılacak iki uyarı

**Uyarı 1 — tavan:**

> Bu ekranda 1 alan çeşitlenebiliyor ve bunların tüm kombinasyonu 2 farklı
> senaryo demek. İstediğiniz 5 senaryodan 3 tanesi aynı seçimleri tekrar eder,
> yalnızca test verileri farklı olur.

Doğru ve dürüst: dropdown'ların üçü tek seçenekli (`Bayi Kodu`, `Döviz Cinsi`,
`Taksit Periyot Birimi`), yalnızca `Fatura Tipi` iki seçenekli. Kombinasyon
sayısı = 1 × 1 × 1 × 2 = **2**. Kalan 3 senaryo veri bakımından farklıdır.

Sayının **çarpım** olması önemli: senaryolar artık kombinasyonları dolaştığı için,
iki tane iki-seçenekli liste 2 değil **4** farklı test demek. "En geniş liste 2
seçenekli" demek onu yarıya düşürürdü.

Planlayıcı yine 5 üretiyor — ama analist ne aldığını biliyor. Dolgu yapan bir
araçla, dolgu yaptığını söyleyen bir araç arasındaki fark bu.

**Uyarı 2 — kısıt yok:**

> Şu alanların kısıtı (max_length / min / max) katalogda yok, değerler varsayılan
> merdivenden geldi — ekran daha kısa bir değer bekliyorsa reddedebilir: Fatura
> No, Orijinal Fatura Numarası, Taksit Adedi, Toplam Tutar, Orijinal Fatura
> Tutarı, Taksit Periyodu, Fatura Dönemi, E-Fatura Hash Kodu. Bu alanlar için
> "Ekranı Keşfet" taraması kısıtı okuyup kataloğa yazar.

Bu uyarı şu an **çok haklı**, ve tablodaki en çarpıcı hücre onu kanıtlıyor:
**`Taksit Adedi` = `"TESTVERISI4"`**. Bu alan taksit sayısı, yani bir sayı. Ama
katalogda `numeric: true` de yok, `max_length` de yok — planlayıcının bilmesinin
hiçbir yolu yok, ve tahmin etmiyor.

Bu, planlayıcının **tek başına çözemeyeceği** tek sorun. Bir metin kutusuna her
zaman kabul edilecek evrensel bir değer yok: rakam yazarsan isim alanı reddeder,
harf yazarsan sayı alanı reddeder. Cevap kataloğa yazılmak zorunda.

Çözüm: ekranı bir kez "Ekranı Keşfet" ile tarayın. Tarama `maxlength`, `min`,
`max`, `type=number`, `inputmode` okuyup kataloğa yazar:

```yaml
  - label: "Taksit Adedi"
    type: text
    max_length: 3
    numeric: true
```

Sonrasında aynı alan `1 / 12 / 6 / 2 / 12` gibi değerler alır.

### 6.6 Kart başlıkları nereden geliyor

`label`, planlayıcının kendi ürettiği kısa bir Türkçe etiket — **modelin yazdığı
`Scenario:` başlığından değil**. Sebep: modelin başlığı doğrulanmamış metin, ve
arayüzün listesi ona bağlanmamalı.

Etiket **gerçekten değişen** seçeneklerden kuruluyor — yani seçenek listesi birden
fazla olan alanlardan. Bu bir düzeltme: önce "ilk iki seçenek" alınıyordu, ve bu
katalogda ilk iki dropdown (`Bayi Kodu`, `Döviz Cinsi`) tek seçenekli olduğu için
**beş kart da aynı başlığı** taşıyordu (`"Bayi Kodu = 93002 · Döviz Cinsi = TL"`),
gerçekten değişen `Fatura Tipi` ise 4. sırada olduğu için kırpılıp atılıyordu.
Birbirinden ayırt edilemeyen bir başlık, başlıksızlıktan kötüdür.

Hiç seçim çeşitlenmiyorsa — **bu üç katalogda da durum bu** — başlık en tanınır
veri değerine düşüyor: tutar, yoksa tarih. Yani DBS için kartlar şöyle geliyor:

```
  ① Fatura Tipi = Normal          ← Fatura Tipi iki secenekli, basligi o kuruyor
  ② Fatura Tipi = E-Fatura
  ③ Fatura Tipi = Normal
  ④ Fatura Tipi = E-Fatura
  ⑤ Fatura Tipi = Normal

Banko'da (hic secim yok, basliga veri dusuyor):
  ① 1. Ödeme Tutarı 9.999,99
  ② 2. Ödeme Tutarı 1,00
  ③ 3. Ödeme Tutarı 0,01
```

Son çare `"Senaryo 3 · farklı test verisi"` — tutar ve tarih de yoksa. O da
dürüst: söylenecek başka bir şey yok.

---

## 7. Faz 3 — 5 model çağrısı

Planlayıcı 5 kombinasyon verdi. Şimdi her biri için bir senaryo yazdırılacak.

### 7.1 Döngü

```
for i = 0 … 4:                       ← EŞZAMANLI (llm.batch-concurrency kadar)
    ┌──────────────────────────────────────────────────────────────────┐
    │ prompt = SYSTEM + USER(katalog, intent, login, VARYASYON i)      │
    │                                                                  │
    │ (1) MODEL ÇAĞRISI ───────────────────────►  vLLM                 │
    │              ham metin  ◄───────────────────────                 │
    │                                                                  │
    │     ····· settle(): modelden geleni DÜZELTEN üç adım ·····       │
    │ (2) LlmOutputCleaner.clean()   think blogu, fence, önsöz         │
    │ (3) LoginStep.apply()          login satırını ZORLA              │
    │ (4) DateVerb.apply()           tarih alanının FİİLİNİ düzelt     │
    │ (5) EntryRow.apply()           `click "<ızgara satırı>"` →       │
    │                                sil, ya da `select row` yap       │
    │     ·······················································       │
    │                                                                  │
    │ (6) ScenarioValidator.validate(taslak, sözlük, katalog, intent)  │
    │                                                                  │
    │     geçerli mi? ── EVET ──► bitti, bu senaryo 1 çağrıya mal oldu │
    │          │                                                       │
    │          └─ HAYIR ──► (7) ONARIM TURU                            │
    │                       prompt = SYSTEM + repair(taslak, hatalar)  │
    │                       (1)…(6) bir kez daha                       │
    │                       hâlâ geçersizse: taslağı YİNE DÖNDÜR       │
    └──────────────────────────────────────────────────────────────────┘
```

Son satır önemli: geçersiz bir taslak **yine de** döndürülüyor, hatalarıyla
birlikte. Neredeyse-doğru bir senaryo, analistin editörde düzeltebileceği bir
şeydir; hata sayfası değildir.

**(3)(4)(5) neden var — ve (7) neden yetmiyor.** Üçü de prompt'un zaten *rica
ettiği* şeyler. Fark şu: (7) ONARIM TURU modele hatayı yazıp düzeltmesini
*istiyor*, (3)(4)(5) ise düzeltmeyi *yapıyor*. Bu ayrımın bedeli ödendi — tarih
fiilleri promptta iki kez anlatılmıştı, model yine `enter` yazdı, sonra
doğrulayıcının kendi şikâyetini kendisine geri okuyan bir onarım turundan geçip
yine `enter` yazdı. Bu yüzden `DateVerb` var. Aynı şey ızgara satırında
tekrarlandı (`click` + `select row` yan yana), bu yüzden `EntryRow` var.

Ölçüt: doğru cevap **katalogdan deterministik olarak okunabiliyorsa** (tek
argüman, aynı konum, yanlış yapacak bir şey yok) yeniden yazıcı yazılır; insan
kararı gerekiyorsa doğrulayıcı raporlar ve analist düzeltir. Üçü de sözlükle
korunuyor: dağıtımın yayınlamadığı bir adıma asla yeniden yazmıyorlar.

### 7.2 Prompt anatomisi

```
╔═══════════════════════ SYSTEM TURU ═══════════════════════╗
║ (5 senaryonun HEPSİNDE birebir aynı)                      ║
║                                                           ║
║ "You write Cucumber/Gherkin test scenarios for a Turkish  ║
║  internet banking application..."                         ║
║                                                           ║
║ HARD RULES — 11 madde:                                    ║
║   1. Yalnızca listedeki desenleri kullan                  ║
║   2. {string} → çift tırnak, {int} → çıplak sayı          ║
║   3. Tırnak içindeki her değer KATALOGDAN gelmeli         ║
║      (yalnız test verisi uydurulabilir: tutar, tarih...)  ║
║   4. Yalnız .feature yaz — açıklama, fence, <think> YOK   ║
║   5. Tek Scenario (gerekmiyorsa Outline yok)              ║
║   6. Başlıklar Türkçe, adım metni İngilizce desen         ║
║   7. navigation bloğunun HER parçası senaryoda olmalı     ║
║      (entry_tab / entry_row atlamanın en sessiz hatası)   ║
║   8. KATALOGDAKİ HER ALANI DOLDUR, sırayla                ║
║      — istisna 1: readonly                                ║
║      — istisna 2: shown_when                              ║
║   9. TİP FİİLİ BELİRLER (text→enter, amount→enter amount, ║
║      dropdown→select…from, account_picker→select account) ║
║  10. Yalnız KENDİ yazdığın değeri doğrula                 ║
║  11. ARGÜMAN SIRASI ("from" varsa değer önce)             ║
║                                                           ║
║ AVAILABLE STEP PATTERNS:          ◄── CANLI SÖZLÜK        ║
║   Given login with cif {string} and userid {string}       ║
║   When  navigate to {string} {string}                     ║
║   ... (20 desen, executor'dan HTTP ile gelmiş)            ║
║                                                           ║
║ SHAPE OF A VALID SCENARIO: (yapı örneği)                  ║
╚═══════════════════════════════════════════════════════════╝

╔═══════════════════════ USER TURU ═════════════════════════╗
║ (senaryo başına DEĞİŞEN kısım burada)                     ║
║                                                           ║
║ SCREEN CATALOG (YAML):                                    ║
║   app: NGC                                                ║
║   flow: Online DBS Fatura Oluştur                         ║
║   navigation: ...                                         ║
║   fields: ...   ◄── KATALOĞUN TAMAMI, OLDUĞU GİBİ         ║
║                                                           ║
║ WHAT TO TEST:                                             ║
║   Bu ekranda fatura oluşturmayı test etmek istiyorum      ║
║                                                           ║
║ THE LOGIN STEP IS ALREADY DECIDED — do not write one:     ║
║   Given login with cif "DI00002018" and userid "3014"     ║
║                                                           ║
║ ┌─── VARYASYON BLOĞU — SADECE BU 5'TE FARKLI ───────────┐ ║
║ │ BU SENARYONUN VERİ KOMBİNASYONU — bunlar seçildi,     │ ║
║ │ değiştirme ve kendin başka değer uydurma:             │ ║
║ │   "Bayi Kodu" = "93002"                               │ ║
║ │   "Başlangıç Vadesi" = "06/11/2026"                   │ ║
║ │   "Fatura No" = "TEST-2"                              │ ║
║ │   ...                                                 │ ║
║ │   "Fatura Tipi" = "E-Fatura"                          │ ║
║ │   "E-Fatura Hash Kodu" = "TEST-2"                     │ ║
║ │                                                       │ ║
║ │ BU SENARYODA DOKUNULMAYACAK ALANLAR — hiçbir adımda   │ ║
║ │ adını bile geçirme, ne doldur ne doğrula:             │ ║
║ │   (bu senaryoda yok)                                  │ ║
║ └───────────────────────────────────────────────────────┘ ║
║                                                           ║
║ Write the .feature file now. Output nothing but the file. ║
╚═══════════════════════════════════════════════════════════╝
```

Varyasyon bloğu **USER turunda**, SYSTEM'de değil. Sebep: kurallar her senaryoda
aynı ve modelin en çok ağırlık verdiği yerde durmalı; varyasyon ise her çağrıda
değişiyor. SYSTEM'e koymak, tek bir değişen paragraf için uzun promptu 5 kez
yeniden kurmak olurdu.

Blok **kapalı bir liste** olarak yazılmış. "Değerleri çeşitlendir" bir ricadır ve
5 aynı dosya üretti; "aynen bunları kullan" bir talimattır, ve uyulduğu
doğrulayıcıdan görülebilir — çünkü her değer kataloğun kendi listesinden geldi.

Üçüncü başlık (`DOKUNULMAYACAK ALANLAR`) işin zor yarısı. Ekranda olmayan bir
`shown_when` alanı 30 saniyelik zaman aşımı, iki kez işaretlenen bir kutu ise
işaretsiz bir kutudur — ikisi de doğrulayıcının göremediği hatalar. Bu yüzden
"yukarıda yoksa dokunma" ima edilmiyor, **açıkça yazılıyor**.

### 7.3 Modelden gelen ham metin ve temizlik

Model tek bir metin döndürüyor. Promptta yasaklanmasına rağmen iki şey
güvenilir biçimde geliyor:

```
<think>
Kullanıcı DBS fatura oluşturma senaryosu istiyor. Katalogda 15 alan var...
</think>
İşte senaryo:
```gherkin
Feature: Online DBS Fatura Oluştur
  Scenario: E-Fatura tipiyle fatura oluşturma
    ...
```
```

`LlmOutputCleaner` üçünü de kesiyor:

| Ne | Neden geliyor |
|---|---|
| `<think>…</think>` | Qwen3 akıl yürütme bloğu; vLLM content alanından aynen geçiriyor |
| ` ```gherkin … ``` ` | Model milyonlarca kod cevabını böyle biçimlenmiş görmüş |
| "İşte senaryo:" önsözü | İlk Gherkin anahtar kelimesinden (`Feature`/`Özellik`/`@`) önceki her şey atılıyor |

Üçü de temizlenmezse Gherkin ayrıştırıcısına **1. satırda sentaks hatası** olarak
varıyor — ve bir onarım turu, gerçek bir hata yerine biçim artığına harcanıyor.

Sonra `LoginStep.apply()`: taslaktaki her `login` satırı silinip doğru satır
senaryo başlığının altına yerleştiriliyor.

### 7.4 Doğrulama

`ScenarioValidator`, **gerçek Cucumber ayrıştırıcısını** ve Cucumber'ın kendi
ifade eşleştiricisini kullanıyor. Yani satır numaraları ve kabul/ret kararı
Cucumber'ın üretecekleriyle **birebir aynı**. Tarayıcısız, ~50 ms.

İki katman:

```
KATMAN 1 — GRAMER (katalog olmadan da çalışır)
  ├─ syntax          Gherkin ayrıştırılamadı
  ├─ undefinedStep   Bu satır 20 desenden hiçbirine uymuyor
  │                  → en yakın 3 desen öneri olarak döner
  └─ unusedOutline   Scenario Outline var ama <placeholder> yok

      ↓ (yalnızca her satır bilinen bir adıma bağlandıysa devam)

KATMAN 2 — KATALOG (katalog varsa çalışır)
  ├─ unknownLabel        "Toplam Tutarı" diye bir alan yok
  │                      → Levenshtein ile en yakın 3 etiket önerilir
  ├─ wrongVerb           dropdown'a `enter` yazılmış
  │                      → `enter "Bayi Kodu" "93002"` GEÇERLİ bir desendir;
  │                        yalnızca tarayıcıda, olmayan bir metin kutusunu
  │                        arayarak başarısız olur. Tipi bilen tek şey katalog.
  ├─ unknownOption       "Dolar" seçilmiş, katalogda yok
  ├─ unearnedAssertion   Hiç yazmadığı bir değeri doğrulamaya kalkmış
  │                      → gerçek vaka: readonly "Bayi Ünvanı" için
  │                        "Ford Otomobil Ticaret A.Ş." uydurdu; ekranda
  │                        "TEST / TEST UNVANI" yazıyordu
  ├─ paraphrasedMessage  success_message'ı kendi kelimeleriyle yazmış
  └─ uncataloguedMessage Katalogda mesaj yok ama bir mesaj doğrulamaya kalkmış
```

Katman 2'nin kapısı önemli: bir satır hiçbir adıma bağlanmıyorsa katalog soruları
sorulmuyor — aynı hatayı iki kez raporlamak gürültüdür.

`unearnedAssertion`'ın bir kaçış kapısı var ve doğru olan: **analist değeri
kendisi yazdıysa** doğrulamak uydurma değil bilgidir. `intent` bu yüzden
doğrulayıcıya kadar taşınıyor.

### 7.5 Onarım turu

```java
llm.ask(system, prompts.repair(draft, result))
```

Onarım promptu **orijinal görevi taşımıyor** — taslağı ve hükmü taşıyor:

```
The scenario you produced was rejected by the validator.

YOUR DRAFT:
Feature: ...
  Scenario: ...
    And enter "Bayi Kodu" "93002"

ERRORS:
  line 8: "Bayi Kodu" bir dropdown; `enter` ile sürülemez -> And enter "Bayi Kodu" "93002"
      closest valid patterns: select {string} from {string}

Rewrite the whole file so every step matches an allowed pattern exactly.
Keep the intent and the values you already chose wherever they were valid.
```

Görevi tekrarlamamak kasıtlı: model ne istendiğini zaten biliyor, tekrarlamak
düzeltme yerine **yepyeni bir senaryo** davet ediyor.

Neden tek tur (`llm.max-repairs=1`): ilk onarım düzeltilebilecek neredeyse her
şeyi düzeltiyor; sonraki turlar çoğunlukla aynı hatayı yeniden kelimeliyor ve
tam bir üretim maliyeti daha çıkarıyor.

### 7.6 Neden 5 ayrı çağrı ve 5 ayrı dosya

Alternatif şu olurdu: tek çağrıda 5 `Scenario:` bloğu içeren tek bir dosya.
Üç sebeple reddedildi — ve birincisi ölçülmüş bir güvenlik deliği:

**① Tek dosya doğrulamayı ZAYIFLATIYOR.**

```java
// ScenarioValidator.collectFromFeature — her senaryonun adımlarını TEK listeye toplar
for (FeatureChild child : feature.getChildren()) {
    child.getScenario().ifPresent(scenario -> steps.addAll(scenario.getSteps()));
}

// checkAssertionsAreEarned — TEK bir "yazılmış etiketler" kümesi
Set<String> written = new HashSet<>();
```

Yani 5 senaryoluk bir dosyada **5. senaryonun `verify`'ı, 1. senaryonun `enter`'ı
sayesinde "hak edilmiş" sayılıyor**. Bu, uydurma `Bayi Ünvanı` değerinin bir
tarayıcı koşusuna kadar gitmesini sağlayan deliğin **aynısı**.

```
TEK DOSYA (5 senaryo)                 AYRI DOSYALAR (5 × 1 senaryo)
┌─────────────────────────┐           ┌──────────┐ ┌──────────┐
│ S1: enter "Fatura No"   │           │ S1       │ │ S2       │
│ S2: ...                 │           │ written: │ │ written: │
│ S5: verify "Fatura No"  │           │ {Fat.No} │ │ {kendi}  │
│     ↑ S1 sayesinde      │           └──────────┘ └──────────┘
│       GEÇİYOR ✗         │            her kontrol tek senaryo
└─────────────────────────┘            için olduğu kadar SIKI ✓
```

**② Her taslak kendi onarım turunu alıyor.** Tek dosyada 3. senaryo bozuksa,
onarım 5'inin hepsini yeniden yazmak zorunda — ve doğru olan 4'ünü bozabilir.

**③ Her senaryo tek tek çalıştırılabilir.** Analist 2. senaryoyu koşup 4.'yü
koşmayabilir.

Yan maliyet yok: çağrılar **eşzamanlı** gidiyor (aşağısı), yani 5 ayrı çağrı
tek çağrıdan belirgin biçimde yavaş değil.

### 7.7 Çağrı aritmetiği ve neden eşzamanlı

```
model çağrısı sayısı  =  count × (1 + llm.max-repairs)   ← ÜST SINIR
                      =  5 × (1 + 1)
                      =  5 ile 10 arası
```

Hepsi geçerli çıkarsa 5. Hepsi bir kez onarım gerektirirse 10.

**Eşzamanlı, `llm.batch-concurrency` (varsayılan 4) kadar.**

Önce sıralıydı ve gerekçesi şuydu: *"tek vLLM dağıtımı var, 5 eşzamanlı istek
aynı GPU'yu paylaşır ve daha erken bitmez."* **Bu gerekçe yanlıştı**, ve tam da
konu olduğu sunucu hakkında yanlıştı:

- **Sürekli yığınlama (continuous batching) vLLM'in varlık sebebi.** Eşzamanlı
  istekler aynı ileri geçişte yığınlanır; sıraya girmezler.
- **Model bunu daha da keskinleştiriyor.** `Qwen3.6-35B-A3B` bir uzmanlar
  karışımı (MoE) ve aktif parametresi 3B. Tek istekte çözümleme bellek bant
  genişliğine takılı kalır, GPU büyük ölçüde boştur. Yığın büyüdükçe o boşluk
  dolar.

Sonuç: 5 eşzamanlı istek, toplam süreyi **beş isteğin toplamına değil, bir
isteğe** yaklaştırır.

```
ESKI (sirali)                      YENI (4'lu esszamanli)
S1 ████████ 20sn                   S1 ████████
S2         ████████ 20sn           S2 ████████   hepsi ayni
S3                 ████████        S3 ████████   ileri gecislerde
S4                         ████    S4 ████████
S5                             ██  S5     ████████
   toplam ~100 sn                     toplam ~30-40 sn
```

**Sınırsız değil, bilerek.** Bu uç nokta başka ekiplerle **paylaşılıyor**;
10'luk bir batch'i tek seferde atmak herkesin isteğini arkaya iter.

**Bir senaryonun patlaması batch'i öldürmüyor.** Çağrılar bağımsız — aralarında
paylaşılan hiçbir şey yok — o yüzden biri hata alırsa yalnızca o taslak kaybolur,
hangisi olduğunu söyleyen bir not eklenir ve diğer dördü gelir. Sessizce kısalan
bir liste, planlayıcının az üretmeye karar verdiği gibi görünürdü.

### 7.8 Katalog ayrıştırılamazsa

```java
VariationPlanner.Plan plan = (catalog == null) ? null : planner.plan(catalog, wanted);
```

Katalog ayrıştırılamıyorsa planlayıcı çalışamaz (alan, tip, seçenek listesi
okuyor ve hiçbiri yok). Batch yine koşuyor — `count` kez aynı istekten — ve
**neden benzer olacaklarını söylüyor**:

> Katalog ayrıştırılamadı, bu yüzden veri kombinasyonu planlanamadı: 5 senaryo da
> aynı istekten üretildi ve birbirine çok benzeyecek. Katalogdaki 'fields'
> bloğunu kontrol edin.

Projedeki her yerdeki aynı tercih: ayrıştırılamayan bir katalog kontrolleri
**kapatır**, isteği başarısız etmez.

---

## 8. Faz 4 — Yanıt ve ekran

### 8.1 Dönen JSON

```json
{
  "count": 5,
  "notes": [
    "Bu ekranda 1 alan çeşitlenebiliyor, en geniş liste 2 seçenekli. İstediğiniz 5 senaryodan sonraki 3 tanesi aynı seçimleri tekrar eder, yalnızca test verileri farklı olur.",
    "Şu alanların kısıtı (max_length / min / max) katalogda yok, değerler varsayılan merdivenden geldi — ekran daha kısa bir değer bekliyorsa reddedebilir: Fatura No, Orijinal Fatura Numarası, Taksit Adedi, Toplam Tutar, Orijinal Fatura Tutarı, Taksit Periyodu, Fatura Dönemi, E-Fatura Hash Kodu. Bu alanlar için \"Ekranı Keşfet\" taraması kısıtı okuyup kataloğa yazar."
  ],
  "scenarios": [
    {
      "index": 0,
      "label": "Bayi Kodu = 93002 · Döviz Cinsi = TL",
      "values":   { "Bayi Kodu": "93002", "Başlangıç Vadesi": "07/10/2026", "...": "..." },
      "ticked":   [],
      "excluded": ["E-Fatura Hash Kodu"],
      "feature":  "Feature: Online DBS Fatura Oluştur\n\n  Scenario: ...",
      "valid":    true,
      "validation": { "valid": true, "stepCount": 21, "errors": [] },
      "attempts": 1,
      "log":      ["attempt 1: valid, 21 steps"]
    },
    { "index": 1, "...": "..." }
  ]
}
```

Her senaryo satırı, arayüzün `/suggest`'ten **zaten tanıdığı** şekilde
(`feature`, `valid`, `validation`, `attempts`) artı onu ayırt eden varyasyon.
`Suggestion` iç içe gömülmedi — gömülse arayüz batch için
`draft.suggestion.feature`, tek senaryo için `draft.feature` okurdu, ki iki
render yolu böyle ayrışmaya başlar.

Taslak üretildiyse **200** dönüyor, geçersiz olsa bile.

### 8.2 Liste → tam ekran → geri

```
   ┌─ ÇALIŞMA ALANI · liste modu ──────────────────────────────┐
   │ Senaryo              [ 5 senaryo ] [Doğrula] [Çalıştır]   │  ← ikisi devre dışı
   │ ┌────────────────────────────────────────────────────────┐│
   │ │ Üretilen senaryolar                        5 senaryo   ││
   │ ├────────────────────────────────────────────────────────┤│
   │ │ ⚠ Bu ekranda 1 alan çeşitlenebiliyor...                ││ ← notes
   │ │ ⚠ Şu alanların kısıtı katalogda yok: Taksit Adedi...   ││
   │ ├────────────────────────────────────────────────────────┤│
   │ │ ① Bayi Kodu = 93002 · Döviz Cinsi = TL   [21 adım]     ││
   │ │   (Fatura Tipi: Normal) (Toplam Tutar: 1,00) (⊘E-Fat..)││ ← çipler
   │ │   Feature: Online DBS Fatura Oluştur                   ││ ← ilk 4 satır
   │ │     Scenario: Normal tipte fatura oluşturma            ││
   │ ├────────────────────────────────────────────────────────┤│
   │ │ ② ...                                    [1 hata]      ││ ← BAĞIMSIZ rozet
   │ │ ③ ...                                    [21 adım]     ││
   │ └────────────────────────────────────────────────────────┘│
   └───────────────────────────────────────────────────────────┘
                              │
                   ② kartına tıklanıyor
                              ▼
   ┌─ ÇALIŞMA ALANI · taslak modu ─────────────────────────────┐
   │ Senaryo              [ 1 hata ]  [Doğrula] [Çalıştır]     │  ← ikisi aktif
   │ [← Tümü] Senaryo 2 / 5  (Fatura Tipi: E-Fatura) (...)     │  ← multiBar
   │ ┌──┬─────────────────────────────────────────────────────┐│
   │ │ 1│ Feature: Online DBS Fatura Oluştur                  ││
   │ │ 2│                                                     ││
   │ │ 3│   Scenario: E-Fatura tipiyle fatura oluşturma       ││
   │ │ 4│     Given login with cif "DI00002018" ...           ││
   │ │ 8│     And enter "Bayi Kodu" "93002"       ← kırmızı   ││
   │ └──┴─────────────────────────────────────────────────────┘│
   │ ┌────────────────────────────────────────────────────────┐│
   │ │ Satır 8  "Bayi Kodu" bir dropdown                      ││
   │ │ Bunu mu demek istediniz: select {string} from {string}  ││
   │ └────────────────────────────────────────────────────────┘│
   └───────────────────────────────────────────────────────────┘
```

Üç durum, ikisi birbirini dışlıyor:

| Durum | `multiList` | editör | `multiBar` | Doğrula/Çalıştır |
|---|---|---|---|---|
| Tek senaryo (`/suggest`) | gizli | görünür | gizli | aktif |
| Batch · liste | **görünür** | gizli | gizli | **devre dışı** |
| Batch · taslak açık | gizli | görünür | **görünür** | aktif |

Liste modunda düğmelerin devre dışı olması önemli: açık olsalar editörün
batch'ten **önce** taşıdığı metni çalıştırırlardı.

### 8.3 `saveActiveDraft()` neden her tuş vuruşunda

```js
el.editor.addEventListener("input", function () {
    saveActiveDraft();      // ← drafts[active].feature = editor.value
    syncGutter();
    scheduleValidate();
});
```

Yalnızca "← Tümü"ye basınca kaydetmek yeterli değil: analist yan sekmeyi
değiştirebilir, yeniden üretebilir, sekmeyi kapatabilir. Böyle kaybolan bir
düzenleme **sessizce** kaybolur ve kart hâlâ modelin orijinal metnini gösterir.

Aynı mantıkla `renderValidation()`, taslak modunda sonucu
`drafts[active].validation`'a da yazıyor — yazmasa karttaki rozet bayatlardı.

Ve `showListMode()` bekleyen doğrulama zamanlayıcısını iptal ediyor: son tuş
vuruşundan kalan bir `validate`, kimsenin bakmadığı bir metin hakkındaki
hükümle rozeti ezerdi.

### 8.4 `state.origin` — sessizce kapanabilen kontroller

Her taslak kendi `origin`'ini (`app`, `flow`, `intent`) taşıyor ve `openDraft`
onu `state.origin`'e yüklüyor. Sonra `originScreen()` bunu `/validate` ve
`/runs` çağrılarına koyuyor.

Bu olmasaydı katalog kontrolleri **sessizce kapanırdı** — ve o sessizlik tam
olarak uydurma `Bayi Ünvanı` değerinin tarayıcıya kadar gitmesinin yolu.

---

## 9. Faz 5 — Koşu

### 9.1 Tek taslak çalışır

```http
POST /api/v1/runs
{
  "feature": "Feature: Online DBS Fatura Oluştur\n...",
  "app":     "ngc",
  "flow":    "dbs-fatura-olustur",
  "intent":  "Bu ekranda fatura oluşturmayı test etmek istiyorum"
}
```

`app`/`flow`/`intent` **taşınıyor** ve bu taşıyıcı. Daha önce yalnızca `feature`
gönderiliyordu, yani koşu kapısı gramer kontrolü yapıyor ama **katalog
kontrollerini yapamıyordu** — katalog olmadan yapılamazlar. Sonuç: üretecin
zaten işaretlediği bir taslak buradan geçiyordu.

> **Kural:** bir yeniden-kontrol, tekrarladığı kontrol kadar **en az o kadar
> sıkı** olmalı. Olmazsa taslağı düzenlemek, hatalarını aklar.

### 9.2 Ne olabilir

```
POST /api/v1/runs
      │
      ├─ sözlük yok                 → 503
      ├─ senaryo geçersiz           → 422 + satır satır hatalar
      ├─ tüm executor'lar meşgul    → 503 "All executors are busy"
      └─ kabul                      → 202 {runId, executor, stepCount}
                                         │
                      GET /api/v1/runs/{id}  her 2 saniyede
                                         │
                      ┌──────────────────┴──────────────────┐
                      │ RUNNING → adım zaman çizelgesi canlı │
                      │ DONE / FAILED → artefaktlar çekilir  │
                      └─────────────────────────────────────┘
```

### 9.3 "Tümünü Çalıştır" neden yok

3 executor var ve **kuyruk yok**. 5 koşu birden başlatılsa 4. ve 5. istek
doğrudan `NO_FREE_EXECUTOR` → 503 alırdı. Sıralı bir "tümünü çalıştır" mümkün
ama kendi işi: ilerleme göstergesi, iptal, kısmi başarısızlık raporu gerekir.

---

## 10. Süreler: ne hızlı, ne yavaş

| Adım | Süre | Neden |
|---|---|---|
| Sözlük (önbellekten) | ~0 ms | bellekte |
| Sözlük (ilk kez) | ~50 ms | executor'a bir HTTP |
| Katalog diskten okuma | ~1 ms | küçük YAML |
| **`VariationPlanner.plan()`** | **~1 ms** | düz Java, model yok, I/O yok |
| Prompt kurma | ~1 ms | string birleştirme |
| **Bir model çağrısı** | **10-30 sn** | uzak vLLM, ~5-8 bin token prompt |
| `LlmOutputCleaner` | ~1 ms | regex |
| **Bir doğrulama** | **~50 ms** | gerçek Gherkin ayrıştırıcısı, tarayıcı yok |
| **5 senaryo (hepsi geçerli)** | **~30-40 sn** | 5 çağrı, 4'lü eşzamanlı |
| **5 senaryo (hepsi onarım gerektirdi)** | **~60-80 sn** | 10 çağrı, 4'lü eşzamanlı |
| Bir koşu (tarayıcı) | **~2-5 dk** | gerçek Edge, gerçek banka |
| **MCP ajanı (katalog)** | **2-10 dk** | ~50 araç çağrısı, her biri bir model turu |
| **DOM taraması (katalog)** | **1-5 dk** | ekran başına 4 sn bekleme + alan başına diff |

**Uzun süren çağrılar — üçü, hepsi senkron:**

1. `POST /api/v1/explore/catalog` — MCP ajanı. En uzun. İptal ucu **yok**.
2. `POST /api/v1/scenarios/suggest-many` — batch. `count` ile doğrusal.
3. `POST /api/v1/discovery` + durum yoklama — DOM taraması.

Üçü de senkron HTTP. Bu bir eksiklik değil ama bir sınır: kurumsal bir vekil
sunucu uzun isteği keserse arayüz sessizce bekler. Asenkron bir iş modeli (id,
depo, yoklama) yalnızca birisi "başlat ve git" isteyince yapılmaya değer.

---

## 11. Üretilen her şey nerede durur

```
cucumber-uplift-test/
├── catalogs/ngc/dbs-fatura-olustur.yaml     ← Faz 0 çıktısı, GIT'TE, elle okunmuş
├── .mcp-browser-profile/                    ← canlı banka oturum çerezleri, ASLA commit
└── runs/mcp/                                ← MCP sunucusunun çıktı dizini

Executor'ın diskinde (koşu başına bir dizin):
  {runDir}/scenario.feature                  ← çalıştırılan metin
  {runDir}/report.html                       ← Cucumber HTML raporu
  {runDir}/run.log                           ← konsol günlüğü
  {runDir}/traces/<senaryo>.zip              ← Playwright trace, onlarca MB
  {runDir}/catalog-draft.yaml                ← yalnızca keşif koşularında

Orchestrator'ın H2 veritabanında (%LOCALAPPDATA%/uptest/uptest.mv.db):
  TEST_RUN, TEST_RUN_STEP                    ← koşu geçmişi, yeniden başlatmayı atlatır
```

**Veritabanında OLMAYAN şeyler** — ve bu bilinçli bir eksik:

| Ne | Nerede | Sonucu |
|---|---|---|
| Üretilen `.feature` metni | Yalnızca **tarayıcının belleğinde** (`state.drafts`) | Sekme kapanınca 5 taslak gider. Koşulan senaryo `scenario.feature` olarak executor'da kalır. |
| Katalog keşif koşuları | `ConcurrentHashMap` | Orchestrator yeniden başlarsa kurtarılamaz, ekran yeniden gezilmeli |
| Modelin ne ürettiği kaydı | Hiçbir yerde | — |

`SCENARIO_DRAFT` ve `DISCOVERY_RUN` tabloları **tasarlandı ama yazılmadı**
([`PROJECT.md`](../PROJECT.md) yol haritası, madde 1).

---

## 12. Bağlam ve "memory şişmesi" — üç net cevap

Soru buydu: *"ajanın memory'si şişiyor mu?"* Cevap yola göre değişiyor.

### 12.1 MCP Ajanı — EVET, şişiyor, ve bir koşuyu öldürdü

Araç döngüsünün doğası: **her araç sonucu, görevin sonuna kadar konuşmada
kalıyor.** Model 30. turda hâlâ 1. turun snapshot'ını görüyor.

```
tur 1:  system + user                                        ~6.000 token
tur 2:  system + user + [navigate çağrısı] + [sonuç]         ~6.500
tur 3:  system + user + ... + [SNAPSHOT: 40.000 karakter]   ~18.000  ← patlama
tur 4:  ... + [SNAPSHOT]                                     ~29.000
tur 5:  ... + [SNAPSHOT]                                     ~40.000
...
tur 20: ... + 18 snapshot                                   >131.072  ✗ ÖLDÜ
```

Gerçekten oldu. `BudgetedTool.trim()`'in javadoc'unda yazılı:

> *"around the twentieth one a run simply stopped: 'maximum context length is
> 131072 tokens ... your prompt contains at least 131073'. Nothing was wrong with
> the flow; it ran out of room half-finished, after minutes of real browser
> time."*

**İki azaltıcı var, ikisi de koda yazılı:**

**① Her araç sonucu 12.000 karaktere kırpılıyor** (`LARGEST_RESULT`):

```java
return result.substring(0, LARGEST_RESULT)
        + "\n\n[... kesildi. Bu sayfa tek bir cevaba sigmayacak kadar buyuk. "
        + "Aradigin sey burada yoksa TUM sayfayi yeniden okuma: metinle arayan araci "
        + "kullan, ya da snapshot'i target/depth ile daralt.]";
```

Sınır bilerek cömert — bir formun tüm alan listesi hayatta kalmalı, yoksa çare
yürüyüşün amacını siler. Durdurduğu şey **sınırsız** durum. Ve not **modele
yazılmış**: neyin kaybolduğunu ve ne kullanması gerektiğini söylüyor, çünkü
sessizce kısaltılan bir sayfa modeli yalnızca kesilmiş bir alanı aramaya
gönderir.

**② Sistem promptu snapshot almamayı öğretiyor:**

> *"LOOKING FOR ONE ELEMENT? SEARCH, DO NOT SNAPSHOT. ... A full snapshot of one
> of these pages is enormous, EVERY one you take stays in this conversation for
> the rest of the task, and a run that takes twenty of them simply runs out of
> room and dies half-finished."*

**Ayrıca döngünün kendisi bağlı:** bütçe 250 çağrı, ikinci navigasyon reddi,
`submit_catalog`'un `returnDirect`'i. Bunlar şişmeyi değil **döngüyü**
sınırlıyor; ikisi birlikte koşuyu bitirilebilir kılıyor.

**Kalan risk:** 12.000 × 20 snapshot = 240.000 karakter ≈ 60-80 bin token. Pencere
131.072. Yani çok uzun bir akış hâlâ sıkışabilir — sadece 20. turda değil, daha
sonra. Bu yüzden ajan **dakikalar** sürüyor ve **tekrarlanabilir değil**.

### 12.2 Senaryo Üretici — HAYIR, şişmiyor. Durumsuz.

```java
public String ask(String systemMessage, String userMessage) {
    return chat.prompt()
            .system(systemMessage)
            .user(userMessage)
            .call()
            .content();
}
```

Dikkat: **geçmiş yok.** Her çağrı tek system turu + tek user turu. Araç yok,
dolayısıyla araç sonucu yok, dolayısıyla birikecek bir şey yok.

```
5 senaryo = 5 BAĞIMSIZ KONUŞMA

  çağrı 1:  [system ~4.000 token] + [user ~2.500 token]  →  cevap  →  BİTTİ, atıldı
  çağrı 2:  [system ~4.000 token] + [user ~2.500 token]  →  cevap  →  BİTTİ, atıldı
  çağrı 3:  ... aynı
```

Prompt boyutu **sabit**: `count` 5 de olsa 10 da olsa her çağrının promptu aynı
büyüklükte. Katalog + sözlük + 11 kural + varyasyon bloğu.

**Onarım turu bile geçmişe eklemiyor:**

```java
llm.ask(system, prompts.repair(draft, result))
```

Aynı `ask`, yine tek user turu — sadece o turun **içeriği** taslağı ve hataları
içeriyor. Konuşma geçmişi olarak değil, metin olarak. Yani onarım turunun
promptu ilk turun promptundan **küçüktür** (katalog yerine taslak taşıyor).

Pratik sonuç: **çoklu senaryo üretiminde bağlam sorunu YOK.** `count`'u
artırmanın maliyeti süre ve token, bellek değil.

### 12.3 DOM Taraması — model yok, soru geçersiz

`CatalogDiscovery` düz Java + Playwright. Hiç LLM çağrısı yapmıyor. "Bağlam"
diye bir şey yok; durumu `LinkedHashMap`'ler (`catalog`, `currentScreen`,
`buttonScreens`, `effects`) ve bunlar koşu sonunda ölüyor.

### 12.4 Token bütçesi — kaç token, ne maliyet

Artık **ölçülüyor**: `LlmClient` her çağrının `usage` metaverisini okuyor ve
logluyor, batch toplamı `/suggest-many` yanıtında `tokens` alanı olarak dönüyor
ve "Çoklu Üret" durum kutusunda görünüyor. Eskiden bu sayı hiçbir yerde yoktu —
`.call().content()` metaveriyi atıyordu.

Ölçülen prompt parçaları (karakter):

| Parça | Karakter | ≈ Token |
|---|---|---|
| Ajan `SYSTEM` + `CATALOG_RULES` + `SCHEMA.md` | 24.301 | ~6.500 |
| Araç sonucu üst sınırı (`LARGEST_RESULT`) | 12.000 | ~3.000 |
| Üreteç 11 kural | 13.725 | ~3.700 |
| Katalog YAML (DBS) | 2.044 | ~550 |

**Ajan kuadratik harcıyor** — her tur önceki tüm geçmişi yeniden gönderiyor:

```
~50 arac cagrisi, sonunda ~70-80k baglam
toplam ≈ 50 tur × ort. 40k baglam ≈ 2.000.000 prompt token   ← BIR kesif
```

**Üreteç sabit harcıyor** — durumsuz, araçsız:

```
cagri basina ≈ 4.800 prompt + ~800 completion
5 senaryo × (1 + max 1 onarim) ≈ 25.000-50.000 token          ← BES senaryo
```

**Bir ajan keşfi ≈ 40-80 kat bir batch üretimi.** Bu oran, DOM taramasını
(sıfır token) arayüze bağlamanın neden en değerli iş olduğunu tek başına
anlatıyor.

**Para cinsinden maliyet yok.** Model bankanın kendi vLLM dağıtımında; fatura
çıkaran bir satıcı yok. Gerçek para birimi üç şey: duvar saati, GPU doluluğu
(başka ekiplerle paylaşılıyor — `llm.batch-concurrency`'nin sınırlı olmasının
sebebi bu), ve 131.072 tokenlık bağlam tavanı.

### 12.5 Özet tablo

| | MCP Ajanı | Senaryo Üretici | DOM Taraması |
|---|---|---|---|
| Model turu sayısı | ~50 | 1 (+1) | 0 |
| Geçmiş birikir mi | **Evet** | **Hayır** | — |
| Prompt büyür mü | **Evet, tur tur** | **Hayır, sabit** | — |
| Pencere sınırına çarptı mı | **Evet, ölçüldü** | Hayır | — |
| Azaltıcı | 12.000 kırpma + "ara, snapshot çekme" + 250 bütçe | gerek yok | — |
| `count`/süre artınca bağlam | — | **değişmez** | — |

---

## 13. Ne bozulabilir

Faz faz, ve analistin ne gördüğü.

### Faz 0 — Katalog

| Belirti | Sebep | Ne yapmalı |
|---|---|---|
| `problems`: "dropdown ama hic secenek yazilmamis" | Bu uygulamada kapalı bir açılır liste **hiçbir şey içermiyor**; liste tıklanınca kuruluyor. Ajan açmadan okudu | Ajana listeyi açmasını söyle, ya da DOM taramasıyla karşılaştır |
| `problems`: "success_message var ama hicbir alan verifiable degil" | Ajan sonuç ekranına ulaştı ama onay ekranını kaydetmedi | Akışı yeniden yürüt, `note_confirmation`'ı vurgula |
| `problems`: "Alanlar verifiable ama success_message yok" | Onay ekranında durdu, son düğmeye basmadı | Talimatta son onayı açıkça iste |
| `problems`: "Talimat su kontrollere dokunulmasini istedi, ajan hicbirini denemedi" | `ZORUNLU:` adımı atlandı. **En sessiz hata**: sonraki her adım DOĞRU ama YANLIŞ ekranda | Kataloğu kullanma, yeniden yürüt |
| Katalogda `max_length` yok | Ajan taslağı; erişilebilirlik ağacı attribute taşımıyor | **DOM taramasını da koş** |
| Ajan koşusu hiç dönmüyor | İptal ucu yok. Tarayıcıyı kapatmak koşuyu bitiriyor (`CLOSED` yapışkan) | Tarayıcı penceresini kapat |

### Faz 1 — Kapılar

| HTTP | Mesaj | Anlamı |
|---|---|---|
| 400 | `intent is required` | "Ne test edilsin" boş |
| 503 | `No executor reachable, so the step vocabulary is unknown` | Hiçbir executor ayakta değil. **Üretim reddedildi, tahmin edilmedi** |
| 400 | `No catalog for ngc/x` + `available` | Katalog adı yanlış ya da kaydedilmemiş |
| 400 | `userid is required for ngc` | NGC CIF+userid ister |
| 400 | `count en az 1 olmali` | — |
| 400 | `Çoklu üretim için CIF zorunlu` | **Yalnızca `/suggest-many`.** Boş cif = N senaryoda N farklı müşteri numarası; §5.5.1 |
| 502 | `LLM call failed` + `rootCause` | vLLM ulaşılamıyor/yavaş/reddediyor. Kök neden raporlanıyor çünkü Spring AI'nın kendi istisnası neredeyse hiçbir şey söylemiyor |

### Faz 2 — Planlayıcı

| Belirti | Sebep |
|---|---|
| Not: "Bu ekranda 1 alan çeşitlenebiliyor" | Dropdown'lar tek seçenekli. **Doğru ve dürüst** |
| Not: "Şu alanların kısıtı katalogda yok" | DOM taraması koşmamış. Sayısal bir alana `"TEST1"` gider |
| Not: "Şu alanlar hiçbir senaryoda doldurulmadı" | `shown_when` ebeveyni katalogda **sonra** geliyor. Alanları ekran sırasına alın |
| Not: "Katalog ayrıştırılamadı" | `fields` bloğu bozuk. 5 senaryo benzer olur |
| ⚠ `Taksit Adedi = "TESTVERISI4"` | Yukarıdaki 2. not: sayısal bir alana metin. Tarayıcı koşusunda reddedilir. **Planlayıcı tek başına çözemez** — kataloğa kısıt yazılmalı |
| ⚠ Bir senaryoda iki metin alanı aynı değeri aldı | Varsayılan metin merdiveni 5 basamaklı; bir ekranda 6'dan fazla metin alanı varsa kaydırma sarıyor ve bazıları çakışıyor. İki **fatura numarası** çakışmıyor (sıraları farklı), ama uzak iki alan çakışabilir |

### Faz 3 — Üretim

| Belirti | Sebep |
|---|---|
| `attempts: 2` | İlk taslak geçersizdi, onarım turu koştu. Prompt hakkında bir sinyal |
| Bir kart `[1 hata]` | O taslak onarımdan sonra da geçersiz. **Yine de döndürüldü** — editörde düzeltilebilir |
| `undefinedStep` | Model bir adım uydurdu ya da yeniden kelimeledi. Öneri listesi geliyor |
| `wrongVerb` | dropdown'a `enter` yazdı. Tarayıcıda 30 sn zaman aşımı olurdu |
| `unearnedAssertion` | Yazmadığı bir değeri doğrulamaya kalktı — uydurma |
| 5 senaryo da aynı görünüyor | Katalog ayrıştırılamadı (varyasyon planı yok), ya da tavan 1 |

### Faz 4-5 — Ekran ve koşu

| Belirti | Sebep |
|---|---|
| Düzenlemem kayboldu | Olmamalı: `saveActiveDraft()` her tuş vuruşunda. Olursa bu bir hata |
| Liste modunda Çalıştır devre dışı | Kasıtlı — çalıştırılacak aktif taslak yok |
| 422 koşuda | Koşu kapısı üreteç kadar sıkı. Editörde düzenlenmiş bir taslak kontrolü yeniden geçiyor |
| 503 `All executors are busy` | 3 executor, kuyruk yok |
| Kart rozetleri bayat | `renderValidation` taslağa yazıyor; yazmazsa bu bir hata |

---

## 14. Sık sorulan sorular

Sorulan her soru, kısa cevabı ve bölümü.

| Soru | Kısa cevap | Bölüm |
|---|---|---|
| **Akış nasıl ilerliyor?** | Faz 0 katalog (bir kez) → Faz 1 kapılar → Faz 2 varyasyon planı (Java) → Faz 3 N model çağrısı → Faz 4 liste → Faz 5 koşu | [§3](#3-büyük-resim) |
| **Hangi AI çağrıları atılıyor?** | Yalnızca Faz 3'te: `count × (1 + 1)` = 5 senaryo için 5-10 çağrı. Faz 2'de **hiç** model yok | [§7.7](#77-çağrı-aritmetiği-ve-neden-eşzamanlı) |
| **AI neye sahip?** | Üreticide: 11 kural + 20 adımlık canlı sözlük + kataloğun tamamı + intent + login satırı + varyasyon bloğu. **Araç yok, tarayıcı yok** | [§7.2](#72-prompt-anatomisi) |
| **Hangi HTTP istekleri gidiyor?** | `/suggest-many` (1), executor'a `/cucumber/vocabulary` (yalnız ilk kez), sonra taslak başına `/validate` ve istenirse `/runs` | [§5.2](#52-tarayıcının-gönderdiği-tam-istek), [§9](#9-faz-5--koşu) |
| **Hangi çağrılar uzun sürebilir?** | Üçü: MCP ajanı (2-10 dk), batch üretim (1-5 dk), DOM taraması (1-5 dk). Hepsi senkron | [§10](#10-süreler-ne-hızlı-ne-yavaş) |
| **YAML mı üretiliyor?** | Faz 0'da **evet** (katalog YAML'ı). Faz 3'te **hayır** — Gherkin `.feature` metni üretiliyor | [§4](#4-faz-0--katalog-nereden-geliyor), [§7](#7-faz-3--5-model-çağrısı) |
| **5 şablon nasıl üretiliyor?** | Şablon değil, **5 bağımsız senaryo**. `VariationPlanner` Java'da 5 değer kombinasyonu hesaplıyor, her biri ayrı bir model çağrısına gidiyor | [§6](#6-faz-2--variationplanner-5-kombinasyon-nasıl-bulunuyor) |
| **Değerler nasıl bulunuyor?** | Dropdown/hesap/checkbox: **katalogdan**, `i % k` ile dolaşılıyor. Tutar/tarih/metin: `max_length`/`min`/`max` biliniyorsa ondan türetilen sınır merdiveni, bilinmiyorsa varsayılan merdiven **+ uyarı** | [§6.3](#63-tip-tip-kurallar), [§6.4](#64-gerçek-katalogla-yürütme) |
| **Hepsi tek bir ajan çağrısıyla mı?** | Hayır, üç kez hayır: (1) ajan değil, (2) tek çağrı değil — 5 ayrı, (3) tek dosya değil — 5 ayrı dosya, çünkü tek dosya doğrulamayı zayıflatıyor | [§7.6](#76-neden-5-ayrı-çağrı-ve-5-ayrı-dosya) |
| **Ajanın memory'si şişiyor mu?** | **MCP ajanında evet** — ölçüldü, 131072 token'da bir koşu öldü; iki azaltıcı var. **Senaryo üreticisinde hayır** — durumsuz, prompt sabit boyutta | [§12](#12-bağlam-ve-memory-şişmesi--üç-net-cevap) |
| **Neden modelden "5 farklı senaryo yaz" istemiyoruz?** | `temperature=0.3` aynı isteğe aynı cevabı vermek için ayarlı. Yükseltmek çeşitliliği uydurma etiketle satın alır | [§6.1](#61-neden-modelden-istenmiyor) |

---

## 15. Doğrulanmamışlar ve bilinen eksikler

Dürüstlük bölümü. Buradaki hiçbir madde gizlenmiş değil.

### 15.1 Hiç VDI'da koşmadı

Çoklu üretim kodu yazıldı, derlenmedi ve **tek bir senaryo üretmedi**. Bu
dokümandaki akış kaynak koddan okundu, çalışan bir sistemden gözlenmedi.
Doğrulama adımları `dreamy-inventing-rose.md` planında.

### 15.2 Mevcut katalogda kısıt yok

`catalogs/ngc/dbs-fatura-olustur.yaml` içinde **hiç** `max_length`, `min`, `max`
ya da `numeric` yok. Elle eklenmedi — uydurmak olurdu. Yani bugün 5 senaryo
istenirse `Taksit Adedi = "TEST1"` gelir ve tarayıcıda reddedilir.

**Sıra:** önce "Ekranı Keşfet" ile kataloğu yenile → kaydet → sonra çoklu üret.

### 15.3 Çözülmüş üç kusur — ne olduğu ve neyle değiştirildiği

Bu üçü dokümanı yazarken gerçek katalogla elle yürütülerek bulundu ve
**düzeltildi**. Burada duruyorlar çünkü neden böyle yapıldığı kodun tek başına
anlatmadığı bir şey:

| Eski kusur | Belirtisi | Şimdi |
|---|---|---|
| Etiket ilk iki seçenekten kuruluyordu | Tek seçenekli listeler başlığı dolduruyordu; **beş kart da** `"Bayi Kodu = 93002 · Döviz Cinsi = TL"` | Yalnızca **birden fazla seçenekli** alanlar başlığı kurar; hiç yoksa tutara/tarihe düşer |
| Her alan senaryo numarasını kullanıyordu | S1'de **altı metin alanı da** `"TEST1"`; iki fatura numarası birebir aynı → tekillik kontrolü reddeder | Veri alanları merdivende **kendi sırası kadar** kaydırılır; iki fatura numarası artık farklı |
| Seçim alanları köşegen yürüyordu | İki iki-seçenekli liste → 4 kombinasyonun **2'si hiç test edilmiyor** | Karışık tabanlı sayaç: kombinasyonlar dolaşılır, tavan `çarpım` olarak raporlanır |
| Tarih merdiveninde `-30` vardı | S4'ün `Başlangıç Vadesi` **geçmişte** → uygulama reddeder, koşu boşa gider | Tüm offsetler gelecekte: `0, 30, 365, 180, 90` |
| Metin değerlerinde boşluk/tire | `"TEST VERISI 4"`, `"TEST-2"` → maskeli kutu reddeder | Yalnızca harf+rakam: `TEST1 / TEST22 / TST3 / TESTVERISI4 / T5` |

Tarih kaydırması **bilerek yapılmadı**: denendiğinde `Fatura Tarihi`,
`Başlangıç Vadesi`'nden altı ay sonraya düştü — kimsenin istemediği bir sıralama.
Alan ilişkileri katalogda yazılı değil, o yüzden bir senaryodaki tüm tarihler aynı
günü alıyor.

Hiçbiri VDI'da doğrulanmadı — bkz. [§15.1](#151-hiç-vdida-koşmadı).

### 15.4 Geçmiş tarih artık hiç test edilmiyor

Merdiven `bugün / +1 gün / +3 gün / +1 hafta / +1 ay` — hepsi bugün ya da
sonrası. Geçmiş tarih gerçek bir sınır durumu ve test edilmiyor; bu bir **kayıp**.

Sebep: hangi tarih alanının geçmişi kabul ettiğini katalog söylemiyor.
`Fatura Tarihi` meşru biçimde geçmişte olabilir, `Başlangıç Vadesi` olamaz —
vadesi geçmiş bir fatura reddedilir. Etiket kelimesinden tahmin etmek bu sınıfın
yapmadığı şey.

Geri getirmek için katalogda yeni bir anahtar gerekir, örneğin
`accepts_past: true`. Yapılmadı.

Not: `enter date` adımı `"dün"` ve `"-1 hafta"` gibi **geçmiş ifadeleri destekliyor**
— analist bir senaryoyu elle düzenleyip geçmiş tarih yazabilir. Üretmeyen şey
planlayıcı, adım değil.

### 15.5 Eşzamanlılık sınırı istek başına, sunucu başına değil

`llm.batch-concurrency=4` **bir batch** içindeki eşzamanlılığı sınırlıyor. İki
analist aynı anda 10'luk batch isterse vLLM'e aynı anda **8** istek gider —
dört değil. Üstelik o uç nokta başka ekiplerle de paylaşılıyor ve onların
yükü hiç görünmüyor.

Pratikte vLLM bunu yığınlayarak karşılar, ama **ölçülmedi**. Sayıyı yükseltmeden
önce o dağıtımın diğer kullanıcılarına ne olduğuna bakın.

Ayrıca: `tokens` alanı iki eşzamanlı batch'te birbirine karışır —
`LlmClient.spent()` süreç geneli bir sayaç ve fark alınarak hesaplanıyor. Analiste
gösterilen "~43.000 token" için doğru takas; kesinlik istek kapsamlı bir bağlam
gerektirirdi ve değiştireceği bir karar yok.

### 15.6 Asenkron iş yok

Üç uzun uç nokta da senkron. Kurumsal bir vekil sunucu uzun isteği keserse
arayüz sessizce bekler ve kullanıcı "takıldı" görür. Asenkron bir model
(iş id'si + depo + yoklama) yalnızca birisi "başlat ve git" isteyince yapılmaya
değer.

### 15.7 `side-discover` ölü markup

`index.html` içinde "Ekranı Keşfet" paneli duruyor ama `data-side="discover"`
sekmesi yok ve `showSide` onu tanımıyor. Backend (`DiscoveryController`,
`CatalogDiscoveryService`, `PW/discovery/`) **çalışır durumda**. Yani DOM
taraması API'den çağrılabilir, arayüzden çağrılamaz. Karar bekliyor: geri
bağlanacak mı, silinecek mi.

Bu [§15.2](#152-mevcut-katalogda-kısıt-yok)'yi doğrudan etkiliyor — kısıtları
kataloğa yazacak olan tarama, şu anda arayüzden erişilemez durumda.

---

## İlgili dokümanlar

- [`CLAUDE.md`](../CLAUDE.md) — test tarafı: adım sözlüğü, page object'ler,
  Select2, NGC/NGI kuralları, kazanılmış dersler
- [`uptest-executor/src/main/java/server/CLAUDE.md`](../uptest-executor/src/main/java/server/CLAUDE.md)
  — sunucu tarafı: 20 uç nokta, eşzamanlılık, dağıtım
- [`PROJECT.md`](../PROJECT.md) — hedefler, teslimatlar, yol haritası
- [`VOCABULARY.md`](../VOCABULARY.md) — 20 adımın analist/LLM yüzü
- [`catalogs/SCHEMA.md`](../catalogs/SCHEMA.md) — katalog şeması, hangi anahtar
  zorlanıyor hangisi yalnızca tavsiye
- [`catalogs/README.md`](../catalogs/README.md) — tek senaryo üretiminin elle
  tarifi ve promptun neden bu şekilde olduğu
- [`docs/SUNUM-BAGLAMI.md`](SUNUM-BAGLAMI.md) — anlatı / sunum bağlamı
