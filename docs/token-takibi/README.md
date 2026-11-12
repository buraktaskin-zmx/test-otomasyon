# Token Takibi — ne gösterilir, nereden gelir

Sol raydaki **Token Takibi** paneli, model çağıran her AI işini bir **oturum**
olarak gösterir: kaç tur sürdü, her turda modele ne gitti, model ne cevap verdi,
hangi tool çağrılarını istedi, bunlar ne kadar sürdü ve token nereye harcandı.

Kural `docs/dashboard/README.md` ile aynı: **verisi olmayan rakam gösterilmez.** Tahmin olan
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
  parantezli not bunu söyler. Token'ı yine her turda ödenir (§2, "Şablon / ek"; ölçümü `docs/ai-ile-calistir/09-tarihce-ve-dersler.md` §23.3).
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
  CIF ve kullanıcı kodu maskelenmez (test ortamı değerleri, `docs/dashboard/README.md` §9).
- Görüntüler saklanmaz; yerine "[görüntü 1/2: 340 KB PNG]" satırı durur.
- **Budanmaz.** `runs/mcp/` gibi büyür; saklama süresi `docs/dashboard/README.md` açık soru 5.

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

**Ölçüm (11.11.2026):** tek senaryo 52 tur, 1.478.146 girdi token'ı. Hiçbir
tur 131.072'lik pencereye yaklaşmadı; maliyet, ajanın **her turda bütün
konuşmayı yeniden göndermesinden** geliyordu: tool sonuçları %66, tool
tanımları %15, system prompt %7.

Alınan önlemler, sonraki ölçümler ve denenip geri alınanlar artık tek yerde,
güncel tutulan tarihçede:
[`docs/ai-ile-calistir/09-tarihce-ve-dersler.md`](../ai-ile-calistir/09-tarihce-ve-dersler.md).
Bu bölümün eski tam metni orada §23.3 olarak duruyor. Mekanizmaların kendisi:
[04-araclar-ve-kapi.md](../ai-ile-calistir/04-araclar-ve-kapi.md) (kapı, bütçe,
takılma) ve [05-sayfa-ve-baglam.md](../ai-ile-calistir/05-sayfa-ve-baglam.md)
(sayfa özeti, eski sonuçların kısaltılması).

Panelde bu yolun izi: oturumun "Bütçe" kartı ve listede "bütçe aşıldı"
işareti, `uptest.explore.ai-run.token-budget` (100.000) hedefine göre.
