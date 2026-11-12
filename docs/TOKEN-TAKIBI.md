# Token Takibi — ne gösterilir, nereden gelir

Sol raydaki **Token Takibi** paneli, model çağıran her AI işini bir **oturum**
olarak gösterir: kaç tur sürdü, her turda modele ne gitti, model ne cevap verdi,
hangi tool çağrılarını istedi, bunlar ne kadar sürdü ve token nereye harcandı.

Kural `DASHBOARD.md` ile aynı: **verisi olmayan rakam gösterilmez.** Tahmin olan
her sayının yanında "≈" durur.

---

## 1. Oturum nedir

Bir düğmeye bir basış = bir oturum. Ajan 40 tur da sürse "AI ile çalıştır" tek
oturumdur; "Senaryo Üret" 25 senaryoyu 4 paralel çağrıyla da üretse tek oturumdur.

| Tür (`kind`) | Panelde | Nereden açılır |
|---|---|---|
| `ai-run` | AI ile çalıştır | Kayıtlı Senaryolar → senaryo → **AI ile çalıştır** (`POST /api/v1/explore`, `source: "ai-run"`) |
| `gherkin` | Gherkin oluştur | Bir ajan koşusundan sonra **Gherkin oluştur**. Ait olduğu koşunun **alt oturumu** olarak da görünür (bant kimliğinden) |
| `explore` | Ekran Keşfi | Ekran Keşfi → Serbest Gözlem |
| `catalog` | Katalog üret | Ekran Keşfi → katalog |
| `flow-read` / `flow-plan` / `flow-suggest` | Ekran okuma / Planlama / Senaryo üretimi | Senaryo Üret'in üç aşaması |
| `health` | Sağlık kontrolü | Üst bardaki model noktası; sayfa her açıldığında bir kez. **Varsayılan listede gizli** |
| `other` | Diğer | Bir oturum açılmadan yapılan model çağrısı — kaybolmasın diye kendi oturumu olur |

Bir oturum orchestrator çalışırken yarıda kalırsa (yeniden başlatma) açılışta
**bilinmiyor** olur (`AiTraceReconciler`), turları silinmez.

---

## 2. Rakamlar ve kesinlikleri

| Rakam | Kaynak | Kesin mi |
|---|---|---|
| Girdi / çıktı / toplam token (tur ve oturum) | Model sunucusunun her çağrıya döndürdüğü `usage` | **Kesin** |
| Önbellekten | Sunucunun bildirdiği önbellek okuması. Bildirmiyorsa "—" ("sunucu bildirmiyor"), **0 değil** | Kesin ya da yok |
| Girdinin kırılımı: system / tool tanımları / kullanıcı / tool sonuçları / model geçmişi | Her mesaj modelin kendi tokenizer'ıyla sayılır (vLLM `POST /tokenize`) | **Kesin** — tokenizer'a ulaşılabiliyorsa |
| Aynı kırılım, tokenizer'a ulaşılamıyorsa | Oturumun **kendi** ilk turundan ölçülen karakter/token oranıyla tahmin | **≈** |
| Şablon / ek | Sunucunun girdi rakamı **eksi** parçaların toplamı: sohbet şablonunun kendi işaretleri. Tahmin modunda negatif çıkabilir | Kalan |

**"Şablon / ek" nedir, neden kısaltılamaz.** Bu satır bizim gönderdiğimiz bir
metin değil: model sunucusu, mesajları modele vermeden önce kendi sohbet
şablonundan geçirir ve o şablon her mesajın başına/sonuna işaret
(`<|im_start|>rol`, `<|im_end|>`), araç listesinin etrafına bir sarmalayıcı,
her araç çağrısının etrafına `<tool_call>`, her sonucun etrafına
`<tool_response>` ekler. Biz yalnız içeriği sayabiliyoruz; sunucunun bildirdiği
girdi ile parçaların toplamı arasındaki fark bu işaretlerdir.

Dolayısıyla doğrudan kısaltılamaz — **mesaj sayısına ve araç sayısına** bağlı
olarak düşer. Bir turu ortadan kaldıran her şey (koddan giriş, eylemden sonra
sayfanın kendiliğinden gelmesi, `do_steps`) bu satırı da düşürür; modele
sunulan her araç ise onu her turda bir miktar yükseltir. Bir koşuda %20
görünmesi "bir yerde israf var" demek değil, "22 tur çok fazla mesaj demek"
anlamına gelir.
| Tool çağrısının "token"ı | Modelin **geri aldığı** metnin token'ı (BudgetedTool kırptıktan sonra) | Kırılımla aynı |
| Süreler | Turun ve tool'un kendi duvar saati | Kesin |

- **Görsel model turları kırılmaz.** Görüntü metin olarak sayılamaz; toplamları kesin, parçaları yok.
- Kırılım arka planda hesaplanır; yeni bir turun parçaları birkaç saniye sonra gelir.
- "Bağlam büyümesi" grafiğinde taralı sütun = kırılımı (henüz) olmayan tur.

---

## 3. Panelde ne var

**Liste:** dönem (24 saat · 7 gün · 30 gün · tümü) ve tür süzgeci; toplam token,
oturum sayısı, AI ile çalıştır başına ortalama, önbellek payı, en pahalı oturum;
günlük girdi/çıktı grafiği; türe göre dağılım; oturum tablosu.

**Oturum:** başlık (senaryo yolu, CIF, bant kimliği, model), KPI şeridi, ve:
- **Bağlam büyümesi** — her tur bir sütun, girdisi kaynağına göre bölünmüş.
  Ajan her turda bütün geçmişi yeniden gönderdiği için sütunlar büyür; büyümenin
  hangi renkten geldiği (çoğu zaman tool sonuçları = sayfa snapshot'ları) burada okunur.
  Sütuna tıklamak turu aşağıda açar.
- **Token nereye gitti** — oturumun toplam kırılımı ve tool başına sonuç token'ı.
- **Zaman çizelgesi** — tur tur: girdi (↑), bir önceki tura göre artış, çıktı (↓),
  önbellek (⟲), süre, istenen tool'lar. Açılınca: modelin cevabı, düşüncesi
  (reasoning), istediği her tool çağrısı (argüman, tam sonuç, sonuç tipi, süre) ve
  **"bu turun tam girdisi"**: modele giden her mesaj, token'ıyla. Varsayılan
  olarak yalnızca o turda **yeni** olan mesajlar gösterilir; geri kalanı önceki
  turlardan aynen yeniden gönderilmiştir.

**Canlı:** bir oturum çalışırken panel 2 saniyede bir yenilenir; bitince durur.
Kayıtlı Senaryolar'da ajan çalışırken **"Canlı izle →"**, bitince **"Token ayrıntısı →"**
o oturumu doğrudan açar.

---

## 4. Nasıl yakalanıyor

- **Model turları:** Spring AI 2.0 her model çağrısını — ajanın kendi tool
  döngüsündeki her turu dahil — bir `ChatModelObservationContext` ile sarar.
  `AiTraceConfig` bir `ObservationRegistry` tanımlar ve ona yalnız
  `AiTraceObservationHandler`'ı bağlar. Ajan döngüsüne dokunulmadı.
  (Spring AI'ın prompt'ları konsola basan kendi handler'ları bağlanmaz.)
- **Tool çağrıları:** `BudgetedTool.guarded` — eylem bandına yazan aynı yer.
- **Görsel model:** düz HTTP olduğu için `VisionClient` turu kendisi bildirir.
- **Oturum bağlamı** thread'e bağlıdır (`AiTraces`); Senaryo Üret'in paralel
  havuzları işleri `AiTraces.wrap` ile sarar.
- **Kayıt hiçbir AI çağrısını bozamaz:** her hata yutulur, oturum başına bir kez loglanır.

## 5. Ne saklanır, nerede

| Nerede | Ne |
|---|---|
| H2: `AI_SESSION`, `AI_TURN`, `AI_TOOL_CALL` | Rakamlar, süreler, 1000 karakterlik önizlemeler |
| Disk: `runs/llm/<oturum>/` (`uptest.trace.dir`) | Tam metinler: `msg/<sha>.txt` her metin **bir kez** (40 turluk bir koşu sistem prompt'unu 40 kez yazmaz), `turns/<n>.json` turun hangi mesajları gönderdiği, `msg/<sha>.tok` token sayısı, `session.json` başlık |

- **Şifre ve SMS kodu** diske ve veritabanına yazılmadan önce maskelenir — eylem
  bandının iki kuralıyla aynı (`SecretMask`, eskiden `ActionTape`'in içindeydi).
  CIF ve kullanıcı kodu maskelenmez (test ortamı değerleri, `DASHBOARD.md` §9).
- Görüntüler saklanmaz; yerine "[görüntü 1/2: 340 KB PNG]" satırı durur.
- **Budanmaz.** `runs/mcp/` gibi büyür; saklama süresi `DASHBOARD.md` açık soru 5.

## 6. Ayarlar

```
uptest.trace.dir=./runs/llm
uptest.trace.tokenize.enabled=true
uptest.trace.tokenize.url=        # boş: spring.ai.openai.base-url'den "/v1" atılarak
```

Açılışta log: `[uptest] ai-trace: AI cagrilari kaydediliyor -> …`. Tokenizer'ın
ilk cevabında `/tokenize calisiyor` ya da `/tokenize kullanilamiyor … (≈)` yazar.

## 7. Uç noktalar

```
GET /api/v1/ai-traces?period=7d&kind=ai-run,gherkin
GET /api/v1/ai-traces/{id}
GET /api/v1/ai-traces/{id}/turns/{seq}/messages
GET /api/v1/ai-traces/{id}/content/{sha}      → text/plain, maskelenmiş
```

`POST /api/v1/explore`, `/explore/catalog` ve `/explore/gherkin` cevaplarına
`traceId` eklendi (isteğe bağlı alan; eski istemciler etkilenmez).

## 8. "AI ile çalıştır" neden pahalıydı, ne yapıldı

**Ölçüm (11.11.2026, `firma-fatura-islemleri/10-...salt-okunur`):** 52 tur,
1.478.146 girdi token'ı, 5.176 çıktı. Hiçbir tek tur 131.072'lik pencereye
yaklaşmadı (en büyüğü 52.667) — maliyet, ajanın **her turda bütün konuşmayı
yeniden göndermesinden**: tool sonuçları %66 (her biri küçük, ama sonraki her
turda tekrar gidiyor), tool tanımları %15, system prompt %7. Turların yarısı
bekleme (11), arama (10) ve ağ trafiği (4) — ve bunları prompt'un kendisi
istiyordu ("SEARCH, DO NOT SNAPSHOT", "wait a few seconds … READ THE PAGE AGAIN").

**Yapılanlar — yalnız "AI ile çalıştır"; Ekran Keşfi aynen:**

| | Ne | Nerede |
|---|---|---|
| 1 | Her turda yalnızca **son** tool sonucu tam gider; eskiler çalıştırılan Playwright satırına iner. Giriş sonrası ilk ekran da ikinci turda kısalır. Bant tam sonuçları tutmaya devam eder | `CompactingAdvisor` |
| 2 | Giriş ve menü **koddan**, executor'ın seçicileriyle; başarısızlıkta ajana düşer. Şifre ve SMS kodu artık modele gitmez | `AiRunPrelude` |
| 3 | Araçlar daraltıldı: bekleme, arama, ağ, konsol, ekran görüntüsü yok | `ExploreController.AI_RUN_TOOLS` |
| 4 | Her eylemden sonra sayfa kodla okunur ve sonuca eklenir (`uptest.explore.settle-ms`) | `AiRunPolicy` |
| 5 | Kısa system prompt (~1/3) | `AgentPrompts.RUN_SYSTEM` |
| 6 | **Tavan** 100.000 token: %80'de ajana "bitir" notu, aşılınca `report_result` dışındaki her araç reddedilir — koşu durur ve yapabildiğini raporlar | `uptest.explore.ai-run.token-budget` |
| 7 | Giriş koddan yapılamazsa ajan **hiç başlatılmaz**; sebep (ağ / sayfa) adıyla söylenir | `AiRunPrelude.diagnose` |
| 8 | Sayfa modele **özetlenerek** verilir: adı olan ve tıklanabilen her şey kalır, adsız iskelet ve kod yankısı atılır | `LeanResults`, `uptest.explore.ai-run.page-cap` |
| 9 | Senaryoda geçmeyen bir **onay** (Onayla/Evet/Gönder…) tıklanamaz — banka kaydı geri alınamaz | `BudgetedTool.unaskedCommit` |
| 10 | Sayfa **düz liste**: girinti, `[ref=]` parantezleri ve ızgara hücreleri gider, her şey tek satıra iner. Ölçüldü: 20 satırlı fatura ızgarası 7.187 → 1.665 karakter (**4,3x**) | `LeanPage`, `uptest.explore.ai-run.page-cap` |
| 11 | **Birkaç adım tek turda**: `do_steps` adım listesini alır, sarılmış araçlardan sırayla geçirir, ilk hatada ve ekran yeni bir şey söylediğinde durur, sayfayı sonda bir kez döndürür | `StepRunner`, `uptest.explore.ai-run.max-steps` |
| 12 | `browser_fill_form` kaldırıldı: `do_steps` aynı işi denetimli yapıyordu, şeması yedinin en büyüğüydü ve tanımlar her turda gidiyor | `ExploreController.AI_RUN_TOOLS` |

Gherkin oluştur etkilenmez: bant, koddan yapılan menüyü tek bir `prelude_menu`
kaydı olarak tutar ve renderer bundan `navigate to` satırını üretir.

**İlk ölçüm (11.11.2026, 22 tur, 124.817 girdi):** kısaltma işe yaradı — tool
sonuçları %66'dan **%26'ya** düştü (971.062 → 32.393). Geriye kalan en büyük
kalem **tool tanımları: 49.478 token, %40**, tur başına ~2.250. Bunlar her
turda yeniden gitmek zorunda (modele söylenmeyen aracı çağıramaz), o yüzden
sıklık değil **uzunluk** kısaldı: `LeanTools` her aracın açıklamasını ilk
cümlelerine indiriyor ve şemasından yalnızca düzyazıyı atıyor — tip, zorunlu
alanlar, enum, argüman adları olduğu gibi kalıyor
(`uptest.explore.ai-run.describe-cap`). Yalnız "AI ile çalıştır"; Ekran Keşfi
her kelimeyi görmeye devam ediyor.

Kalan kalemler aynı ölçümde: system prompt 13.786 (%11, tur başına ~620 —
`RUN_SYSTEM` öncesi ~1.950'ydi), model geçmişi 12.675 (%10), şablon 10.081
(%8), kullanıcı mesajı 6.484 (%5).

**İkinci ölçüm (12.11.2026):** tanımlar kısaldıktan sonra koşu yine 100 bini
aştı ve senaryonun adını verdiği düğmeye **varmadan kesildi**. Dağılımda tool
sonuçları **%50**, şablon/ek **%20**. Tool sonuçlarının neredeyse tamamı tek
bir şey: her turda gönderilen güncel sayfa, 6.000 karakterlik YAML ağaç. O
ağacın karakterlerinin dörtte üçü bilgi değildi — on seviye derindeki bir
düğmenin satır başındaki yirmi boşluğu, `[ref=e8]`'in iki işe yarar karakteri
etrafındaki altı tanesi, ve yirmi satırlık bir ızgaranın yüz altmış satırlık
`cell`/`generic` sargısı. Bu yüzden ağaç artık **düz listeye** yazılıyor
(`LeanPage`, 10. satır) ve kararı verilmiş adımlar **tek turda** yürüyor
(`StepRunner`, 11. satır).

Düz listenin ağaçtan **daha** bilgili olduğu bir yer var: bu ekranlarda form
kontrollerinin erişilebilir adı yoktur, etiket kardeş bir `generic`'tir — ağaçta
model iki satırı komşuluktan eşleştirmek zorundaydı, şimdi etiket kontrolün
yanında köşeli parantez içinde duruyor (`e15 textbox [Fatura No]`).

Hiçbir koşulda atılmayanlar: ref'i olup tıklanabilen her şey (ızgara hücresinin
içindekiler dâhil), adı ya da metni olan her düğüm, her `alert`/`status`/`dialog`,
ve snapshot bloğunun **dışındaki** her şey (`### Modal state`, konsol mesajları,
sayfa başlığı, hata metni) — o blok dışına hiç dokunulmaz. `LeanPageTest` ham
snapshot'taki her kontrol ref'inin düz listede de bulunduğunu çiviliyor.

`do_steps` körlemesine bir plan değil, üç şey yüzünden: her adım modelin
çağıracağı **aynı sarılmış araçlardan** geçer (yasak kontrol, senaryoda geçmeyen
onay, kapanmış tarayıcı, token duvarı, bant kaydı aynen işler — aracı modelden
saklamak hiçbir zaman güvenlik sınırı değildi, kontroller sınırdır); yapılmayan
ilk adımda durur; ve her adımdan sonra sayfa okunur (bir MCP çağrısı, sıfır
token), ekran batch başlamadan söylemediği bir şeyi söylemeye başlarsa durur.
Bant alt adımları gerçek araç adlarıyla tutar, `do_steps` kaydı TapeRenderer'ın
sessiz listesindedir — Gherkin, ajan beş tur da harcasa bir tur da harcasa aynı.

Token Takibi'nde oturumun "Bütçe" kartı ve listede "bütçe aşıldı" işareti
hedefe göre durumu gösterir. Araç tanımlarının gerçek maliyeti artık her koşunun
başında loglanıyor (`[uptest] ai-run: N arac tanimi, toplam M karakter`, araç
araç) — MCP sunucusu yalnız banka ağından görülebildiği için tahmin yerine
ölçüm koymanın tek yolu bu.
