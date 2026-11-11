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
| 1 | Her turda yalnızca **son** tool sonucu tam gider; eskiler çalıştırılan Playwright satırına iner. Giriş sonrası ilk ekran da ikinci turda kısalır. Bant tam sonuçları tutmaya devam eder | `CompactingChatModel` |
| 2 | Giriş ve menü **koddan**, executor'ın seçicileriyle; başarısızlıkta ajana düşer. Şifre ve SMS kodu artık modele gitmez | `AiRunPrelude` |
| 3 | Araçlar daraltıldı: bekleme, arama, ağ, konsol, ekran görüntüsü yok | `ExploreController.AI_RUN_TOOLS` |
| 4 | Her eylemden sonra sayfa kodla okunur ve sonuca eklenir (`uptest.explore.settle-ms`) | `AiRunPolicy` |
| 5 | Kısa system prompt (~1/3) | `AgentPrompts.RUN_SYSTEM` |
| 6 | Hedef 100.000 token: %80'de ajana "bitir" notu; aşılırsa **durdurulmaz**, AI Özet'e uyarı | `uptest.explore.ai-run.token-budget` |

Gherkin oluştur etkilenmez: bant, koddan yapılan menüyü tek bir `prelude_menu`
kaydı olarak tutar ve renderer bundan `navigate to` satırını üretir.

**Beklenen** (tahmin, VDI'da ölçülecek): ~6–8 tur, tur başına 5–7 bin, toplam
35–55 bin. Token Takibi'nde oturumun "Bütçe" kartı ve listede "bütçe aşıldı"
işareti hedefe göre durumu gösterir.
