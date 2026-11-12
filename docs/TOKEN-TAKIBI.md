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
  **"bu turun tam girdisi"** — aşağıdaki iki görünüm.

**Canlı:** bir oturum çalışırken panel 2 saniyede bir yenilenir; bitince durur.
Kayıtlı Senaryolar'da ajan çalışırken **"Canlı izle →"**, bitince **"Token ayrıntısı →"**
o oturumu doğrudan açar.

---

## 3b. Bir turun tam girdisi — iki görünüm

Bir tur = modele giden **bir istek**. "Bu turun tam girdisini göster" düğmesi
o isteğin tamamını açar, ve iki şekilde okunabilir; ikisi farklı soruya cevap
verdiği için ikisi de var.

**mesaj mesaj** — *hangi parça pahalı?* Her mesaj kendi satırında: rolü
(system · kullanıcı · model · tool sonucu · tool tanımları · cevap · düşünce),
karakter ve token sayısı, ve metninin ilk 400 karakteri (`MESSAGE_PREVIEW`)
tıklanmadan görünür. "Tamamını göster" gerisini getirir, "Kopyala" o parçayı
kopyalar. Altta **"Bu turdan çıkan"**: modelin cevabı, düşüncesi ve istediği
tool çağrıları.

> **Varsayılan olarak hiçbir mesaj gizli değil.** Eskiden yalnız o turda
> **yeni** olanlar gösteriliyordu; system prompt ve talimat ise yalnızca 1.
> turda "yeni" olduğu için, bu görünümün açılma sebebi olan iki metin 2. turdan
> sonra hep gizliydi. Onay kutusu ("yalnız bu turda yeni olanlar") ters soru
> için duruyor: *bu tur neyi ekledi?*

**tek metin** — *model ne okudu?* İsteğin tamamı, modelin okuduğu sırayla, tek
bir döküm olarak (`TurnTranscript`). Tek HTTP çağrısı, mesaj başına bir tane
değil. **Tamamını kopyala** ve **İndir (.txt)** düğmeleri, iki turu bir
editörde karşılaştırmak için. Dökümün bölümleri:

```
# TUR 3 · <saat> · <süre> · model …          ← başlık: girdi/çıktı/bitiş/mesaj sayısı
===== [1] SYSTEM · 4.102 karakter · 1.148 token · YENİ =====
===== TOOL TANIMLARI (9 araç) · … =====
===== [2] KULLANICI · … =====
===== [3] MODEL · … =====
===== [4] TOOL SONUCU: browser_click · … =====
#################### BU TURDAN ÇIKAN ####################
===== MODELİN CEVABI · … =====
===== MODELİN DÜŞÜNCESİ (reasoning) · … =====
===== İSTEDİĞİ TOOL ÇAĞRILARI (1) =====
```

Bilmeye değer üç şey:

- **Tool tanımları system'den hemen sonra basılır, çünkü şablon onu oraya
  koyuyor.** Mesaj değildir: isteğin kendi `tools` alanında gider. Dökümdeki
  parantezli not bunu söyler. Token'ı yine her turda ödenir (§8, "Şablon / ek").
- **Hiçbir metin kırpılmaz.** Bir sayfa snapshot'ı on binlerce karakter ve asıl
  soru zaten odur; kırpılmış bir döküm "ajan niye böyle yaptı" sorusuna cevap
  veremez. Tarayıcı kaydırır, uzun olan indirilir.
- **Maskeleme burada yapılmaz.** Her metin diske yazılırken `SecretMask`'ten
  geçti (§5), yani panele şifre hiç ulaşmıyor — iki ayrı yerde iki kural
  tutmak gerekmiyor.

Eski oturumlar da açılır: yeni hiçbir şey saklanmıyor, ikisi de `turns/<n>.json`
ve `msg/<sha>.txt`'yi okuyor (§5).

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
GET /api/v1/ai-traces/{id}/turns/{seq}/transcript  → text/plain, turun tamamı (§3b)
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
| 10 | Açılır listede **arama**: hedefsiz yazan `browser_press_sequentially` ajana sunulur; hatalı bir `target` ne yapılacağını söyleyen bir cevapla döner | `AI_RUN_TOOLS`, `BudgetedTool.targetHint` |
| 11 | Ekran koddan açıldıysa arayüzün "… ekranına git." açılış satırı ajana **gitmez** — "Menüye tekrar gitme" ile çelişiyordu | `AiRunPrelude.withoutGoTo` |
| 12 | **Aynı başarısız çağrı**, arada sayfayı değiştiren başarılı bir eylem yoksa ikinci kez tarayıcıya gitmez; ajana önceki hata ve ne yapacağı söylenir | `AiRunPolicy.outcome` / `failedBefore`, `BudgetedTool.SAME_FAILURE` |

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

Token Takibi'nde oturumun "Bütçe" kartı ve listede "bütçe aşıldı" işareti
hedefe göre durumu gösterir.

### 12.11.2026: hâlâ pahalı — ve denenip geri alınan çözüm

**Ölçüm:** 26 tur, 112.547 token, senaryo bitmedi ("Arama kutusuna 'san'
yazılamadı"). Sebep bütçe duvarının geç gelmesi değil, **ajanın göremediği bir
şey**: Firma Seçimi açılınca sayfa 20.192 karakter oldu, `page-cap` 6.000'de
kesti (`browser_click sonucu 20192 -> 6153`), ve Select2'nin arama kutusu
sayfanın **sonunda** açıldığı için modelin gördüğü kısımda yoktu. Ajan ref'ini
bulamayınca hedef uydurdu (`textbox "Seçiniz"`, `textbox`) ve her birini beş
kez denedi. Düzeltme 10. satır: liste açılınca arama kutusu zaten odakta,
yazmak için hedef gerekmiyor.

**Denendi, geri alındı (`40a2a7f` → `f713b14`):** sayfayı tek satırlık düz
listeye çevirmek (7.187 → 1.665 karakter, 4,3×), `page-cap`'i 2.500'e
indirmek ve birkaç adımı tek turda yürüten `do_steps`. Birim testleri geçti,
gerçek koşuda ajan **körleşti**: sayfa 14.275 karaktere çıktı, 2.500'lük sınır
onu 2.653'e kesti (`14275 -> 2653`) ve aranan menü bağlantısı kesilen kısımda
kaldı; kesme notu "snapshot'ı daralt" dediği için ajan daraltılmış snapshot
döngüsüne girdi. Ayrıntı: `docs/MCP-TARAYICI-SORUNU.md`. Çıkarılan ders: bir sayfa özetinin doğruluğu "ref'ler
korunuyor" testiyle değil, **ajanın bir sonraki eylemi için gereken şeyin
özette kalmasıyla** ölçülür — ve bunu yalnızca gerçek bir koşu gösterir. Sayfa
küçültme yeniden denenirse önce hangi öğenin hangi adımda gerektiği ölçülmeli.

**Düzeltildi (tablo satırı 11), gerçek koşuyla henüz ölçülmedi — talimat
kendiyle çelişiyordu.** Ajana giden ilk mesaj
art arda üç şey söylüyor: `AiRunPrelude` "ekranı açık, menüye tekrar gitme"
diyor; hemen ardından arayüzün eklediği satır (`ProductPanel.tsx`, `category
menüsü altındaki "…" ekranına git.`) tersini söylüyor. Ajan ikincisini
uyguluyor ve zaten açık olan menüye tıklıyor. Çelişkiyi görmenin yolu: Token
Takibi → oturum → tur #1 → "Bu turun tam girdisini göster" → `kullanıcı`
mesajı (§3b).
