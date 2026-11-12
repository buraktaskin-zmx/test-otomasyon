# AI ile çalıştır — İstek ve prelude

[← İçindekiler](README.md) · [← Genel bakış](01-genel-bakis.md) · [Promptlar →](03-promptlar.md)

---

## 4. Arayüz: düğmeden isteğe

**Dosyalar:** `frontend/src/product/ScenarioDetail.tsx` düğmeyi çizer
("AI ile çalıştır" / "Ajan çalışıyor…"). `frontend/src/workspace/side/ProductPanel.tsx`
de `aiRun` ile isteği atar.

**Ön koşul:** üstte bir test kullanıcısı (CIF) seçili olmalı. Yoksa şu toast
çıkar: "Önce yukarıdan bir CIF seçin — ajan onunla giriş yapar".

### 4.1 Gönderilen metin

`text` alanı iki satırlık bir açılışla senaryo dokümanının düz metin halinin
birleşimidir. Düz metni `docAsText`, `lib/scenarioProse.ts` içinde üretir:

```
NAKİT YÖNETİMİ menüsü altındaki "Firma Fatura İşlemleri" ekranına git.
Sonra şu senaryoyu uygula:

Senaryo adı (yalnızca bir isim, yapılacak bir adım DEĞİL): <başlık>

Ön koşul:
- <ön koşul 1>

Adımlar — numara sırasıyla, birer birer uygula. Bir adım bitmeden sonrakine geçme; sonraki bir adımın butonuna önceden basma; burada yazmayan bir butona basma:
1. <adım 1>
2. <adım 2>

Beklenen:
- <beklenen 1>
```

Bu biçimin üç yeri sunucuda **okunur**, dolayısıyla değiştirilirse orası da
değişmeli:

| Metin parçası | Kim okur | Ne için |
|---|---|---|
| İlk satır `… ekranına git.` + `Sonra şu senaryoyu uygula:` | `AiRunPrelude.withoutGoTo` | Ekran kodla açıldıysa bu iki satır ajana **gitmez** ("Menüye tekrar gitme" ile çelişiyordu) |
| `1. …` numaralı adımlar | `ClaimCheck.pressesAskedFor` | "tıkla/bas" içeren adımların tırnaklı adı bantta aranır |
| Tırnaklı adlar | `ClaimCheck`, `LeanResults` (seçenek listesi), `PageChrome` (çerçevede tutulacaklar), `BudgetedTool.unaskedCommit` | Kodun senaryoyu "anlayabildiği" tek yer tırnaklardır |

### 4.2 İstek gövdesi

```json
POST /api/v1/explore
{
  "text":        "<yukarıdaki metin>",
  "url":         "https://ifctest.yapikredi.com.tr/ngc/indexNgc.do",
  "cif":         "<seçili test kullanıcısı>",
  "userId":      "<seçili test kullanıcısı>",
  "password":    "<seçili test kullanıcısı>",
  "smsCode":     "<TEST_SMS_CODE>",
  "mainMenu":    "NAKİT YÖNETİMİ",
  "expected":    ["<Beklenen satırları>"],
  "source":      "ai-run",
  "app":         "NGC",
  "product":     "nakit-yonetimi",
  "flow":        "firma-fatura-islemleri",
  "scenarioPath":"<senaryonun yolu>",
  "screen":      "Firma Fatura İşlemleri"
}
```

`LOGIN_URL` sabittir: NGC için `https://ifctest.yapikredi.com.tr/ngc/indexNgc.do`,
NGI için `https://intest.yapikredi.com.tr/ngi/passResetEnter.do`. `mainMenu`
kategori adının `tr-TR` büyük harfli halidir. `source: "ai-run"`, aynı uç
noktayı kullanan Ekran Keşfi'nin "Serbest Gözlem"inden bu yolu ayıran tek
alandır.

---

## 5. Sunucu girişi: ExploreController

`POST /api/v1/explore` → `ExploreController.explore(Instruction)`.
Yalnızca `uptest.explore.enabled=true` iken vardır.

```
explore(body)
 ├─ text boş → 400
 ├─ instruction = compose(body)          giriş önsözü + analistin metni
 ├─ tape  = ActionTape.open(runs/mcp, "explore", instruction, url, mainMenu, [şifre, sms])
 ├─ trace = AiTraces.open("ai-run", meta{title=scenarioPath, app, product, flow, cif, tapeId})
 ├─ verdict = new RunVerdict()           yalnız ai-run; Serbest Gözlem düz yazıyla biter
 ├─ try
 │    answer = runScenario(...)          §6–§17
 │    aborted? → 409 BROWSER_CLOSED
 │    rendered = TapeRenderer.render(tape.calls(), mainMenu, expected)
 │    ClaimCheck.apply(verdict, body.text(), tape.calls(), rendered.steps())
 │    200 { answer: verdict.asText(), tapeId, gherkin, notes, summary: verdict.toJson(), traceId }
 ├─ catch
 │    BrowserClosed / aborted → 409 BROWSER_CLOSED
 │    diğer → kök sebep + yığın izi LOGA, 502 { error: "Sınıf: mesaj", detail, tapeId, traceId }
 └─ finally (HER yolda)
      tape.finish()      bitiş damgası
      closeBrowser()     browser_close: sonraki koşu temiz sekmeden başlasın
      trace.close()
```

`compose()` çıktısı bu yolda yalnızca **giriş kodla yapılamazsa** ajana
gider:

```
https://ifctest.../indexNgc.do adresine git.
Giriş yap:
- Firma Kodu / Müşteri Kodu = <cif>
- Kullanıcı Kodu = <userId>
- Şifre = <şifre>
- #btnSubmit
Akıllı SMS ekranı gelirse #otpPassword alanına <sms> yaz, tekrar giriş yap.
Ana menü: NAKİT YÖNETİMİ

<analistin metni>
```

### 5.1 runScenario

```
runScenario
 1. opened = new AiRunPrelude(ham MCP araçları, tape, settle).run(login, mainMenu, screen)     §6
 2. opened.reached == NOTHING && cif var  →  throw "Ajan başlatılmadı: <sebep> — ... token harcanmadı."
 3. chrome   = PageChrome.forRun(opened, mainMenu, screen, text)                               §13
 4. userTurn = AiRunPrelude.instruction(chrome.shown(opened, text, pageCap), text, composed, ...)  §7
 5. allowed  = AI_RUN_TOOLS (8 tarayıcı aracı)  (+ browser_navigate yalnız giriş yapılamadıysa)
 6. policy   = new AiRunPolicy(ham browser_snapshot, settle, 100000, 220, 12000)
               policy.chrome(chrome); policy.busyProbe(ham browser_evaluate)
 7. answer = client.prompt()
               .system(RUN_SYSTEM + RESULT_RULES)                                               §8
               .user(userTurn)
               .advisors(new CompactingAdvisor())                                               §14
               .tools(toolsFor(..., policy, allowed, 40))                                       §9–§10
               .call().content()
 8. uyarılar: prelude notu ("Ekran koddan açılamadı, ajan kendisi açtı"), bütçe aşımı
```

**Tek ChatClient:** Ekran Keşfi ile aynı, Spring'in yapılandırdığı istemci
kullanılır. İkinci bir istemci elle kurulduğunda (`ChatClient.builder(...)`)
`DefaultChatOptions → OpenAiChatOptions` ClassCastException'ı çıkmıştı
(efd5763). Kısaltma bu yüzden bir **advisor**'dır.

---

## 6. Prelude: giriş ve menü kodla

**Dosya:** `AiRunPrelude.java`. **Neden var:** ölçülen ilk yürüyüşün ilk ~12
turu giriş ve menüydü. Bu turların hepsi, sonraki her turda yeniden ödeniyordu.
Bunların hiçbiri bir karar gerektirmez. Bir yan kazanç daha var: **şifre ve
SMS kodu modele hiç gitmez.**

Ham MCP araçlarıyla çalışır. Bu yüzden ajanın bütçesinden yemez ve ajanın
hamlesi sayılmaz.

```
run(login, mainMenu, screen)
 │
 ├─ fieldsOf(login)            uygulamaya göre seçici/değer çiftleri; tanınmazsa → NOTHING
 ├─ navigate(url)              ≤ NAVIGATE_ATTEMPTS = 5, YALNIZ geçici ağ hatasında tekrar
 ├─ typeWhenReady(ilk kutu)    ≤ FIELD_ATTEMPTS = 6 (sayfa navigate'ten biraz sonra gelir)
 ├─ type(kalan kutular)        browser_type {slowly:true}  — maskeli kutular gerçek tuş ister
 ├─ click #btnSubmit
 ├─ smsCode varsa: #otpPassword yazılabiliyorsa → click #btnSubmit   (yoksa sorun değil)
 │      bant + trace: "prelude_login" (cif, userId; şifre/SMS YOK)
 │
 ├─ mainMenu/screen yoksa → LOGGED_IN
 ├─ menü ≤ MENU_ATTEMPTS = 3:
 │      click  i:text-is("<ANA MENÜ>")
 │      click  .main-nav a:visible:text-is("<Ekran>")
 │      bant + trace: "prelude_menu" (mainMenu, screen)   → TapeRenderer bundan `navigate to` üretir
 │      açılmadıysa → LOGGED_IN + "menü koddan açılamadı: <hata>"
 │
 └─ loaded(screen)  ≤ LOAD_POLLS = 20:
        bekle (settle 1,5 sn) → BusyPage.waitWhileBusy → snapshot
        sayfada "lütfen bekleyiniz" / "yükleniyor" YOKSA → ON_SCREEN + sayfa
        20 bakışta (~30 sn) gitmediyse → NOTHING + "ekranı 30 saniyede yüklenmedi"
```

### 6.1 Seçiciler (executor'ın NGC.login / NGI.login / CommonActions.navigateTo ile aynı)

| Uygulama | Alanlar | Gönder | SMS |
|---|---|---|---|
| NGC | `#userCode` = cif, `#userId` = userId, `#password` | `#btnSubmit` | `#otpPassword` (varsa) → `#btnSubmit` |
| NGI | `#cifNoLabel` = cif, `#password` | `#btnSubmit` | aynı |
| Menü | `i:text-is("<ANA>")` → `.main-nav a:visible:text-is("<Ekran>")` | — | — |

### 6.2 Sonuç durumları ve ajana etkisi

| `Reached` | Ne demek | Ajan başlar mı | Ajana giden ilk mesaj | Araçlara `browser_navigate` |
|---|---|---|---|---|
| `ON_SCREEN` | Giriş + menü + ekran yüklendi | Evet | "Giriş yapıldı ve … ekranı açık. Menüye tekrar gitme." + senaryo + `<<EKRAN>>` | Yok |
| `LOGGED_IN` | Giriş tamam, menü açılmadı | Evet (uyarıyla) | "Giriş yapıldı. Ana menü: …" + senaryo + sayfa | Yok |
| `NOTHING` + cif var | Giriş yapılamadı **ya da ekran yüklenmedi** | **Hayır** → 502 "Ajan başlatılmadı: …" | — | — |
| `NOTHING` + cif yok | (bu yolda olmaz; UI cif'i zorunlu kılar) | Evet | `compose()` metninin tamamı | Var |

### 6.3 `diagnose`: giriş hatası insanca

| Hata metninde | Mesaj | Kim ne yapar |
|---|---|---|
| `ERR_NAME_NOT_RESOLVED`, `ERR_INTERNET_DISCONNECTED` | "AĞ: … adresi çözemiyor. Kurum ağına / VPN'e bağlı değilsiniz." | VPN'i aç |
| `ERR_CERT…`, `ERR_PROXY…` | "AĞ: adres çözüldü ama bağlantı reddedildi" | Sertifika/proxy; tekrar denemek düzeltmez |
| `ERR_CONNECTION…`, `ERR_TIMED_OUT` | "BAĞLANTI: … 5 denemenin hepsinde kesildi … curl ile aynı adres denenirse o da kesiliyor" | Birkaç dakika sonra tekrar; sürerse ağ ekibi |
| `does not match any elements`, `Timeout` | "SAYFA: adres açıldı ama giriş kutusu gelmedi" | Giriş sayfası değişmiş ya da yavaş |

Tekrar denenen geçici hatalar (`WORTH_RETRYING`): `ERR_CONNECTION_RESET`,
`_CLOSED`, `_ABORTED`, `_FAILED`, `ERR_EMPTY_RESPONSE`,
`ERR_SOCKET_NOT_CONNECTED`, `ERR_TIMED_OUT`, `ERR_NETWORK_CHANGED`. Mac'te VPN
açıkken ifctest bağlantılarının kabaca beşte ikisi kesiliyor (curl ile
ölçüldü, 12.11.2026). Beş deneme bu kayıp oranını ~%1'e indirir.

---

## 7. Ajana giden ilk mesaj

`AiRunPrelude.instruction(...)` üretir. `ON_SCREEN` durumunda gerçek bir
örnek (12.11.2026'da geçen senaryo; ref'ler ve sayfa kısaltılmış):

````
Giriş yapıldı ve "NAKİT YÖNETİMİ" menüsündeki "Firma Fatura İşlemleri" ekranı açık. Menüye tekrar gitme.

Senaryo adı (yalnızca bir isim, yapılacak bir adım DEĞİL): İptal ekranından Geri ile listeye dönülür

Adımlar — numara sırasıyla, birer birer uygula. Bir adım bitmeden sonrakine geçme; sonraki bir adımın butonuna önceden basma; burada yazmayan bir butona basma:
1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
2. Izgarada Ödeme Durumu "Ödenecek" olan ilk satırı seç ve başka satır seçme
3. İşlemler panelindeki "İptal Et" bağlantısına tıkla
4. Açılan iptal ekranında yalnızca "Hayır" düğmesine tıkla

Beklenen:
- "Firma Fatura İşlemleri" listesi yeniden görünür

Şu anki ekran:
<<EKRAN>>
### Page
- Page URL: https://ifctest.yapikredi.com.tr/ngc/...
### Snapshot
```yaml
- (üst bilgi, ana menü ve alt bilgi gösterilmedi: ekran zaten açık)
- heading "Firma Fatura İşlemleri" [level=1] [ref=f1e400]
- generic [ref=f1e419]: Firma Seçimi
- combobox [ref=f1e422]:
  - textbox "Seçiniz" [ref=f1e423]
...
```
<</EKRAN>>
````

Üç ayrıntı önemli:

1. **`withoutGoTo`:** UI'nin "… ekranına git. / Sonra şu senaryoyu uygula:"
   açılışı silinir. Silinmeden önce mesaj "Menüye tekrar gitme" deyip bir
   satır sonra "ekranına git" diyordu, ajan da ikincisini uyguladı (eb64738).
   Bu yüzden yalnız **baştaki** satırlar silinir. Senaryonun içinde aynı
   sözcükler geçse bile dokunulmaz.
2. **Sayfa özetlenmiş gelir:** `PageChrome.shown` → `LeanResults.summarise`.
   Ölçümde ham hali 7.070 karakterdi, özetlenmiş ve çerçevesiz hali 1.734
   (bant 2628c4fd). Ardından 12.000 karakterde kesilir.
3. **`<<EKRAN>>…<</EKRAN>>` işaretleri:** ilk araç sonucundan sonra
   `CompactingAdvisor` bu bloğu "[giriş sonrası ilk ekran kısaltıldı — güncel
   ekran son araç sonucunda]" ile değiştirir. Kullanıcı mesajı her turda
   yeniden gönderilir. Bu yapılmasaydı ilk sayfa koşu boyunca her turda
   ödenirdi.
