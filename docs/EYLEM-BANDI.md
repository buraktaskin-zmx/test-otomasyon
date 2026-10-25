# Eylem Bandı — AI koşusunu bir kez yap, sıfır token ile birebir oynat

Tarih: 2026-10-25 · Durum: **Faz 0 kodlandı, VDI'da koşulmadı** — kalan fazlar tasarım

İlgili belgeler:

- Hedefler ve yol haritası: [`PROJECT.md`](../PROJECT.md)
- Test tarafı kuralları: [`CLAUDE.md`](../CLAUDE.md)
- Aynı eksendeki önceki karar: [`E2E-CERCEVE-DEGERLENDIRME.md`](E2E-CERCEVE-DEGERLENDIRME.md)
  — orada *"bizde olmayan tek gerçek yetenek: koşu anında devralma"* diye
  bırakılan boşluğun cevabı bu dosyadır, ama devralma yoluyla değil **kayıt**
  yoluyla
- Ajan yürüyüşünün bugünkü anlatımı: [`AI-GEZ-VE-KESFET.md`](AI-GEZ-VE-KESFET.md)

---

## Context — hedef ve neden

**Hedef:** "AI ile çalıştır" bir senaryoyu gerçek tarayıcıda modelin
kararlarıyla yürütüyor. Bu yürüyüşte MCP'ye **ne çağrıldıysa, hangi argümanla,
hangi sırayla** kaydedilsin; sonra aynı senaryo **model olmadan, sıfır token
ile** istendiği kadar tekrar oynatılabilsin.

**Token maliyeti bugün koşuda değil.** `RunService.start`
(`orchestrator/run/RunService.java:156-219`) hiç model çağırmıyor — paketin
`orchestrator.llm` içinden tek ithali `LoginStep`, o da saf string yazıcı. Yani
Gherkin koşusu zaten sıfır token. Yakan yer ajan yürüyüşü:

| Nerede | Maliyet | Bant ne yapar |
|---|---|---|
| **MCP ajan yürüyüşü** (`POST /api/v1/explore`) | 250 çağrı bütçesi (`uptest.explore.tool-budget`); her tool sonucu konuşmada **kalıcı** kalıyor — bir koşu 131.072 token sınırına çarpıp yarı yolda ölmüş | **Senaryo başına bir kez** ödenir, koşu başına değil |
| Senaryo üretimi (`SuggestService`) | 1 üretim + en fazla 1 repair (`llm.max-repairs=1`) | Zaten tek seferlik |

Bandın vaadi tek cümle: maliyet **testi ne sıklıkla koşturduğuna değil,
uygulamanın ne sıklıkla değiştiğine** bağlanır.

---

## 1. Bugün ne var

| | |
|---|---|
| AI koşusu girişi | `POST /api/v1/explore`, gövde `Instruction{text, url, cif, userId, password, smsCode, mainMenu}` (`explore/ExploreController.java:393`) |
| Model turu | **Bir** — `client.prompt().system(SYSTEM + ANSWER_RULES).user(instruction).tools(...).call().content()` (`:452` endpoint, `:459-464` çağrı) |
| Geriye dönen | Yalnızca düz metin: `Map.of("answer", answer)` |
| Eylemler kaydoluyor mu | **Hayır.** `BudgetedTool.trace()` (`:410-417`) araç adını ve ham argümanı **stdout'a** yazıyor, 300 karakterde kesiyor, sonuçları hiç yazmıyor: *"Arguments only, never results"* |
| `--save-session` | Kapalı — `.mcp.json`'da yok |
| Araç seti | `.mcp.json`'da `--caps` **yok**, yani yalnızca `core` açık. MCP'nin kendi recorder'ı (`--caps=devtools`) ve `browser_generate_locator` (`--caps=testing`) bugün elimizde değil |

---

## 2. Kanca tek nokta: `BudgetedTool.guarded`

```java
private String guarded(String input, Supplier<String> invoke) {   // BudgetedTool:290
    trace(input);                            // ← araç adı + ham argüman JSON
    String refusal = refuse(input);
    if (refusal != null) return refusal;     // ← reddedilen çağrı da bir olgu
    try   { result = watch(invoke.get()); }  // ← sonuç
    catch (RuntimeException failed) { … }    // ← hata
    return aborted.get() ? closed() : trim(result);
}
```

Sınıfın kendi javadoc'u bu metodu şöyle gerekçelendiriyor: *"One path in and
out, so every guard sees every call."* Aynı cümle bant için de geçerli — **her
çağrı, argümanı, sonucu ve hatası tek metottan geçiyor.** Dolayısıyla yapılacak
iş bir kayıt mekanizması icat etmek değil, var olan kancanın çıktısını log'dan
yapılandırılmış bir dosyaya çevirmek.

`ExploreController.toolsFor(...)` her istek için taze bir sarmalayıcı kümesi
kuruyor (`:272`), yani bant da istek başına doğal olarak izole.

---

## 3. Ham bant olduğu gibi oynatılamaz — ve çözümü

MCP araçları elemanı `target` ile alıyor: ya bir snapshot **ref**'i (`e42`) ya
da bir CSS selector. Kodun kendisi söylüyor: `DateTool.setDate`'in `target`
parametresi *"its reference from a snapshot, or a CSS selector for it"*
(`:79-80`), ve `BudgetedTool.SKIPPED_STEP` modele *"the click tool's `target`
also accepts a CSS selector"* diyor (`:69-80`).

**Ref ömürlüdür** — sayfa her değiştiğinde yeniden numaralanır. Sistem
prompt'unun birinci kuralı tam bunu yasaklıyor: *"never act on a reference from
a changed page."* Yarın `e42`'ye tıklamak başka bir elemana tıklamaktır. Bu
yüzden ham tool bandı **bir replay artefaktı değil**, yalnızca bir denetim izi.

Çözüm, kayıt anında ref'i **anlamlı kimliğe** çevirmek — ve bunun için yeni bir
MCP yeteneği gerekmiyor, çünkü `browser_snapshot` sonuçları da `guarded()`'dan
geçiyor:

```
snapshot sonucu (guarded'dan geçiyor)
    - button  "Devam"      [ref=e42]
    - textbox "Fatura No"  [ref=e51]
              │
              ▼
   ref → (role, erişilebilir ad)   →   banda "action" olarak yazılır
```

Bu satırları ayrıştıran regex **zaten bizde**: `ScreenLabels.CLICKABLE` =
`^\s*-\s+(?:button|link)\s+"..."` ve `FORM_CONTROL` =
`^\s*-\s+(?:textbox|combobox|checkbox|radio|spinbutton|searchbox|slider)\s+"..."`
(`playwright/common/ScreenLabels.java:51-62`). `[ref=…]` yakalaması eklenince
orchestrator, **modelin baktığı snapshot'tan** ref'i role + ada çevirebilir.

Not: bu, kimliği **yükseltmek**. `getByRole(name)` locator önceliğinin en üst
basamağı; ham codegen'in ürettiği CSS/nth yolu en alt basamağı. Yani kayıt,
bandı daha kırılgan değil **daha sağlam** bir adrese taşıyor.

---

## 4. Bandın şeması

```json
{ "tapeId": "…",
  "app": "NGC", "flow": "Firma Fatura Sorgulama",
  "recordedAt": "2026-10-25T10:12:00Z",
  "mcpVersion": "…",
  "outcome": "BEKLENDIGI_GIBI",
  "entries": [
    { "seq": 4,
      "raw":    { "tool": "browser_click", "args": { "target": "e42" }, "ok": true },
      "action": { "verb": "click", "role": "link", "name": "Detay",
                  "within": { "role": "row", "name": "955231" } },
      "step":   "click \"Detay\"" },

    { "seq": 7,
      "raw":    { "tool": "browser_type", "args": { "target": "e51", "text": "FTR-2026-0001" }, "ok": true },
      "action": { "verb": "enter", "role": "textbox", "name": "Fatura No", "value": "FTR-2026-0001" },
      "step":   "enter \"Fatura No\" \"FTR-2026-0001\"" },

    { "seq": 9,
      "raw":    { "tool": "set_date", "args": { "date": "05/11/2026" }, "ok": true },
      "action": { "verb": "enter date", "name": "Başlangıç Vadesi", "value": "+3 gün" },
      "step":   "enter date \"Başlangıç Vadesi\" \"+3 gün\"" }
  ] }
```

Dört tasarım kararı ve gerekçeleri:

- **`raw` denetim için, `action` oynatma için.** Biri "model ne yaptı"
  sorusunun, diğeri "bunu nasıl tekrar ederim" sorusunun cevabı. Tek alanda
  birleştirilirse ikisi de bozulur: denetim izi oynatma uğruna sadeleşir,
  oynatma izi denetim uğruna gürültülenir.
- **Mantıksal seviyede kaydedilir.** `DateTool.setDate` bir mantıksal eylemi
  dört ham MCP çağrısına kendisi çeviriyor (yıl → ay → gün; sıra yük taşıyor,
  çünkü yıl veya ay değişince gün ızgarası yeniden çiziliyor) ve bütçeden bir
  kez düşüyor. Bant da dört satır değil **bir** `enter date` taşır. Aynısı
  Select2 için: "aç, oku, tıkla" üçlüsü tek `select`.
- **`within` alanı belirsizliği taşır.** Snapshot ref'i tek bir elemanı
  gösteriyordu; `getByRole(name)` göstermeyebilir. Ajanın yaptığı ayrımı
  kaybetmemek için kapsayıcı satır/bölüm de yazılır. Pilotun en olası ilk
  bulgusu bu (§7).
- **Tarih anlamıyla yazılır.** Kayıt anında `05/11/2026` görülür ama banda
  `+3 gün` düşer. Literal tarih bayatlıyor — bir taslak 7 Ekim'de üretilip
  `08/10/2026` taşıdığında o hafta doğru, iki hafta sonra geçmişte ve vade
  tarihinin bugünün gerisinde olmasını reddeden bir formda hatalı. `RelativeDate`
  kasten koşu anında çözüyor (`CommonSteps:54`, `:129`).

---

## 5. Oynatma: Gherkin render ana yol

Bandın `step` alanları zaten Gherkin satırı. Render edilen `.feature`
`ScenarioValidator`'dan geçer, Kayıtlı Senaryolar'a girer ve `RunService`
üzerinden koşar — **yeni bir koşu yolu açılmıyor, mevcut motor kullanılıyor.**

Bedava gelen şey büyük, çünkü 22 adımın içinde bedeli ödenmiş dersler var:
`enter` maskeli alanı takvimden sürüyor (`fill()` maskeli input'ta adımı yeşil
yakıp alanı boş bırakıyordu), `select` Select2'yi widget olarak açıyor (hidden
`<select>`'e yazmak aynı sessiz geçişi üretiyordu), `enterAmount` lira/kuruş
ayrımını biliyor, `verifyFieldEquals` normalize **etmiyor**, ve hepsi etkisini
geri okuyor. Ham bir MCP tekrarı bunların hiçbirine sahip değil.

**Artık yol — ve yalnızca ölçümden sonra.** Ajanın `core` setinde vocabulary'de
karşılığı olmayan fiiller var: `browser_press_key`, `browser_hover`,
`browser_navigate_back`, `browser_tabs`, `browser_handle_dialog`, kaydırma.
Bunlar için `TapeSteps` adında ayrı bir step sınıfı kurulabilir ve
**`VocabularyExtractor`'ın yayınladığı vocabulary'den dışlanır** — tam olarak
`discover screen catalog` ve `fill discovered fields:` adımlarının dışlandığı
gibi. Böylece üretici model bu fiilleri asla yazamaz, yalnızca oynatıcı
kullanır. Ama bu sınıf, pilotta **kaç çağrının gerçekten eşleşmediği
ölçülmeden** yazılmaz; liste boş çıkarsa hiç yazılmaz.

Eşleşmeyen bir ham çağrı için üçüncü yol yok: **uydurulmaz, reddedilir** ve
adıyla rapor edilir — `EntryRow` ve `DateVerb` yazıcılarının zaten izlediği
disiplin: mekanik olanı düzelt, yargı gerekeni bildir.

---

## 6. Login banda girmez

Ajan login'i gerçekten yapıyor ve `compose()` CIF, kullanıcı kodu, şifre ve SMS
kodunu talimata yazıyor (`ExploreController:414-424`) — hatta `#btnSubmit` ve
`#otpPassword` selector'ları o metnin içinde. Ham bant bu yüzden **şifre ve OTP
taşır**. `Redactor` (`orchestrator/run/Redactor.java`) bu tehlikeyi Gherkin
için zaten tanıyıp maskeliyor, ama maskelenmiş bir bant oynatılamaz.

Çözüm mevcut tasarımda hazır: **bant login'den SONRA başlar** — pratikte
`#btnSubmit` sonrası ilk `browser_snapshot`'ta. Oynatmada giriş satırı,
`LoginStep.apply`'ın bugün her üretilen senaryoya graft ettiği şekilde eklenir
ve kimlik bilgisini NGC/NGI sayfa nesnesi taşır. Böylece banttan sırlar tamamen
çıkar ve `Redactor`'a ikinci bir yol açmak gerekmez.

---

## 7. Pilot: Firma Fatura Sorgulama → fatura detayı → alan doğrulama

Akış: firmayı seç → faturaları listele → bir faturayı seç → **Detay** → detay
ekranında bir alanı doğrula.

Mevcut katalog (`catalogs/ngc/dbs-firm-invoice.yaml`) sorgulama ekranında
bitiyor: `Firma Seçimi`, `Hızlı Tarih Seçimi`, ve sorgudan sonra dolan özet
alanları (`Firma Kodu`, `Toplam Fatura Tutarı`, `Onaylı Tutar (TL)`). **Fatura
ızgarası ve detay ekranı kataloglanmış değil.**

Pilotun bu akışta olması isabetli, çünkü bandın en net faydasını gösteriyor:

> Detay ekranının kataloğu olmadığı için **bugünkü üretici o senaryoyu
> yazamaz** — `ScenarioValidator.checkAgainstCatalog` bilinmeyen etiketi
> reddeder. Ama ajan o ekranı yürüyebiliyor. Bant, **kataloğu olmayan bir ekran
> için koşabilir bir senaryo üretir.**

### Beklenen iki bulgu

İkisi de kodda önceden görülebiliyor, yani pilot bunları keşfetmek için değil
doğrulamak için koşulacak.

**(1) "Detay" belirsiz olacak.** `CommonActions.click`
`getByRole(BUTTON).or(LINK)` kuruyor ve `count() > 1` olduğunda **aday sayısını
log'a yazıp yine ilkine tıklıyor** (`:92-99`). CLAUDE.md'de kayıtlı olay aynı
kelime üzerinedir: Alıcı Listesi İşlemleri'nde "Detay" **on sekiz kez** geçiyor
ve belge sırasındaki ilki uygulamanın gizli tuttuğu bir `div` içinde —
`verifyTextIsDisplayed` bu yüzden `filter(setVisible(true))` ile düzeltildi ama
`click` düzeltilmedi. Ajanın snapshot ref'i tekti; `click "Detay"` tek değil.
Bandın `within` alanı (§4) bu ayrımı taşır; `click` tarafında ya satır kapsamlı
bir çözümleme ya da açık bir `TapeSteps` fiili gerekir.

**(2) Doğrulama "hak edilmemiş" sayılabilir.** Detay ekranı salt okunur:
senaryo oraya hiçbir şey yazmıyor, dolayısıyla
`ScenarioValidator.checkAssertionsAreEarned` (`:264`) değer taşıyan her
`verify`'ı reddeder. Mekanizma zaten var — `CatalogModel.readOnlyScreen()` ve
`ReadOnlyAssertion.apply(...)` tam bu durum için yazılmış, ve `verify the {}
field is masked` salt okunur ekranda kullanılabilen tek değer taşımayan iddia
olarak duruyor. Render'ın salt-okunur bayrağını doğru kurması gerekiyor;
kurmazsa **doğru bir bant geçersiz Gherkin'e** dönüşür.

### Pilot verileri

Render'a literal olarak girecek; burada yer tutucu:

| Ne | Yer tutucu |
|---|---|
| CIF / userid | mevcut test kullanıcısı |
| Firma | `select "<firma>" from "Firma Seçimi"` |
| Tarih aralığı | `select "<aralık>" from "Hızlı Tarih Seçimi"` |
| Seçilecek fatura | `select row "<fatura no>"` |
| Detay | `click "Detay"` (+ `within`: hangi satır) |
| Doğrulanacak alan ve değer | `verify the "<alan>" field equals "<beklenen>"` |

---

## 8. Bant, insan onayından geçmeden regresyon testi olmaz

Tasarımın en önemli kuralı. Atlanırsa proje kendi güven temelini kaybeder.

- **Bant ajanı güvenilir yapmaz, tekrarlanabilir yapar.** Ajan yanlış butona
  bastıysa bant sonsuza kadar yanlış butona basar. Sessiz yanlış işlem bir
  banka ekranında en pahalı hata sınıfı — ve pilotun (1) numaralı bulgusu tam
  olarak o sınıftan.
- Bant **yalnızca sonucu doğrulanmış bir yürüyüşten** kaydedilir. `ANSWER_RULES`
  ajanın cevabını `Sonuç: BEKLENDİĞİ GİBİ` / `… DEĞİL` satırıyla bitirmeye
  zorluyor (`ExploreController:766-778`); bu satır bandın `outcome` alanı olur
  ve `DEĞİL` olan bir bant aday bile olmaz.
- Üstüne analistin açık bir hareketi gelir: **"Bu koşuyu kaydet."** İnceleme
  yüzeyi bandın Gherkin render'ı — analist JSON okumaz, on iki satır Türkçe
  Gherkin okur. Katalog için verilmiş karar birebir aynı: *bir taslak, bir insan
  okuyana kadar katalog değildir.*

---

## 9. Bant nerede yaşar

Senaryo kütüğü için verilmiş karar aynen uygulanır: **orchestrator'ın H2'si
asıl yer, dosya ihracat hedefi.**

- `ACTION_TAPE` (+ `ACTION_TAPE_ENTRY`) tabloları, `ScenarioStore`'un yanında.
- "Dosyaya aktar" `tapes/<app>/<flow>/<ad>.json` yazar, git'e girer ve hiçbir
  şeyi silmez — `POST /api/v1/products/export` davranışıyla aynı.
- Bandın Gherkin render'ı normal bir senaryo olarak `SCENARIO`'ya kaydedilir;
  yani bant **ek bir koşu yolu açmaz**, mevcut kütüğü besler.
- Executor **tasarımı gereği hiçbir şey saklamıyor** ve üç replika var; bandı
  oraya koymak üç düğümün ikisinde yok demektir.

---

## 10. Fazlar

| Faz | İş | VDI'da nasıl kanıtlanır |
|---|---|---|
| **0** ✔ kodlandı | `guarded()` içine bant yazıcısı: her çağrının adı, ham argümanı, ok/hata/reddedilme. Çıktı `runs/mcp/<tapeId>/tape.json`; `/explore` ve `/catalog` cevaplarına `tapeId`. Ajanın davranışı **değişmez**. Sınıf: `orchestrator/explore/ActionTape.java`, dizin `uptest.explore.tape-dir` ile taşınabilir | Pilot akış "AI ile çalıştır" ile koşulur; dosya oluşur ve o koşunun tüm çağrıları sırayla görünür. **Henüz koşulmadı** |
| **1** | Ref çözümleme: snapshot sonuçlarından `ref → (role, name)` tablosu (`ScreenLabels` desenlerine `[ref=…]` eklenir), `raw` yanına `action` + `within` | Aynı bantta her `browser_click` satırının yanında rol ve erişilebilir ad görünür; `action` alanında çıplak `e42` kalmaz |
| **2** | `action` → Gherkin render + "Bu koşuyu kaydet". Render `ScenarioValidator`'dan geçer (salt-okunur bayrağı dahil, §7-2) | Pilotun render'ı doğrulamayı geçer ve **mevcut koşu yolundan** koşar; sonuç ajan koşusunun sonucuyla aynı — ve ikinci koşu **sıfır token** |
| **3** | Eşleşmeyen çağrı tiplerini ölç ve raporla | Liste basılır. Boşsa Faz 4 **hiç yapılmaz** |
| **4** | Yalnızca ölçülen fiiller için `TapeSteps` (vocabulary dışı) ve `within` için satır kapsamlı `click` | Pilotun "Detay" adımı doğru satırın detayını açar, on sekiz adaydan ilkini değil |
| **5** | `ACTION_TAPE` tabloları + "Dosyaya aktar" | Orchestrator yeniden başlatılır; bant ve render'ı yerinde durur |

Faz 0 ve 1 hiçbir davranışa dokunmuyor. Faz 4'ün gerekliliğine **ölçüm** karar
veriyor, tahmin değil — sıralamanın amacı bu.

---

## 11. Açıkça almayacağımız şeyler

| Fikir | Neden hayır |
|---|---|
| MCP'nin `browser_start_recording`'i | Resmî doküman: *"Start recording actions **the user performs** in the browser"*, ve sayfa öne getirilir. Yani **insanın manuel gezintisini** kaydediyor, ajanın tool çağrılarını değil. Üstelik `--caps=devtools` gerekiyor ve `.mcp.json`'da `--caps` hiç yok. Bizim kancamız (`guarded`) hem doğru şeyi kaydeder hem bizim kodumuzdur |
| `--codegen=java` ile ham Playwright kodu | Analistin okuyamadığı, validator'ın denetleyemediği, akışa gömülü id'li prosedürel kod. Repoda kanıtı duruyor: `playwright/ngc/steps/PWRecord.java` bir codegen karalaması (`#userCode`, `#btnSubmit span`, `#dataform`, `#suggestion0`…), Cucumber glue'da değil ve kimse çağırmıyor. Bir kez denenmiş, kenarda kalmış |
| Bandı ham MCP tekrarıyla oynatmak | Ref'ler ölü (§3) ve ham tekrar `enter`/`select`/`enterAmount` içindeki ödenmiş dersleri atar (§5) |
| Playwright Agents'ın healer'ı | Resmî tanım: kırılan testi koşturur, UI'da eşdeğer elemanı arar, yama önerir ve testi **geçirir**. Bizde devralma testi geçirmemeli; geçerse *"model yeni bir tıklama yolu icat edemez"* güven temeli çöker. Bizdeki karşılığı: adaylar rapora düşer (`ScreenLabels.report`), koşu **yine de kırmızı** döner |
| `browser_storage_state` ile login'i atlama | Oturum çerezi bu bağlamda kimlik bilgisidir (`.mcp-browser-profile` zaten `.gitignore` ve `.dockerignore` ile korunan bir dizin), ve login test edilen adımın kendisi. §6 sorunu zaten çözüyor |
| `browser_route` ile API mock | Bu koşular bankanın kendi backend'ini test etmek için var; mock test edilen şeyi kaldırır |
| Tarayıcı saatini dondurmak | Bankanın sunucu saatini dondurmaz; istemci ile sunucu doğrulaması ayrışır. `RelativeDate` kasten koşu anında çözüyor |

---

## 12. Kaynaklar

- Playwright MCP capabilities (varsayılan yalnızca `core`): <https://playwright.dev/mcp/capabilities>
- Playwright MCP devtools araçları, `browser_start_recording` semantiği: <https://playwright.dev/mcp/tools/devtools>
- playwright-mcp README (`--codegen`, `--save-session`, `--caps`, `--isolated`, `--test-id-attribute`): <https://github.com/microsoft/playwright-mcp>
- Playwright Test Agents (planner / generator / healer): <https://playwright.dev/docs/test-agents>
- Playwright Java `Locator` API (`and()` v1.34, `ariaSnapshot` v1.49, `filter(setVisible)` v1.51; bu projede Playwright 1.58): <https://playwright.dev/java/docs/api/class-locator>
