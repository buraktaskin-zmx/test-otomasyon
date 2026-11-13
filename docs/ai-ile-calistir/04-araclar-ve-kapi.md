# AI ile çalıştır — Araçlar ve kapı

[← İçindekiler](README.md) · [← Promptlar](03-promptlar.md) · [Sayfa ve bağlam →](05-sayfa-ve-baglam.md)

---

## 9. Araç kutusu

`ExploreController.toolsFor(...)` her istek için yeniden kurulur. Bütçe ve
ziyaret kümesi tek bir koşuya aittir. Ayrıca builder paylaşılır ve
`defaultTools` liste ekler, bu yüzden araçlar **isteğe** verilir.

| # | Araç | Kaynak | Ne yapar | Sayfa eklenir mi (`ACTING`) |
|---|---|---|---|---|
| 1 | `browser_snapshot` | MCP | Sayfanın erişilebilirlik ağacı | — |
| 2 | `browser_click` | MCP | ref ya da CSS seçiciyle tık | ✓ |
| 3 | `browser_type` | MCP | Kutuya yaz (`slowly` = gerçek tuş) | ✓ |
| 4 | ~~`browser_fill_form`~~ | MCP | **13.11.2026'dan beri verilmiyor** (ai-run `6fd2b27e`): ajan kuruş kutularını bununla doldurdu, koşu geçti ama Hızlı çalıştır kaydı reddedildi; `fill()` maskeli kutuda da tutmaz | — |
| 5 | `browser_select_option` | MCP | Gerçek `<select>` seçeneği | ✓ |
| 6 | `browser_press_key` | MCP | Tek tuş (F5/Ctrl+R reddedilir) | ✓ |
| 7 | `browser_handle_dialog` | MCP | Tarayıcı diyaloğu (onay sözcüğü engeline tabi) | ✓ |
| 8 | `browser_press_sequentially` | MCP; MCP sunmuyorsa kod (`FocusedTyping`: evaluate ile işaretle, `browser_type` ile yaz) | **Hedefsiz** yazma: odaktaki kutuya (Select2 arama). 0.0.82 Mac'te sunmadı (13.11.2026) | ✓ |
| 9 | `set_date` | `DateTool` | Takvimi yıl → ay → gün sırasıyla kodla sürer | ✓ |
| 10 | `do_steps` | `StepRunner` | ≤8 adımı tek turda, ada göre ([§15](06-do-steps-ve-set-date.md)) | — (kendi sayfasını ekler) |
| 11 | `report_result` | `RunVerdict` | Sonucu bildirir, döngüyü bitirir | — |
| (12) | `browser_navigate` | MCP | **Yalnız** giriş kodla yapılamadıysa | — |

**Verilmeyenler:** `browser_wait_for`, `browser_find`, `browser_network_requests`,
`browser_console_messages`, `browser_take_screenshot` ve ötekiler. İlk ölçümde
52 turun 25'i bunlarla geçmişti. `browser_evaluate` ile
`browser_run_code_unsafe` her yolda kapalıdır (`WITHHELD`). Model bu ikisini
alınca tıklamayı bırakıp JavaScript yazmaya başlamıştı. `browser_evaluate`'i
yalnızca kod kullanır (`BusyPage.PROBE`). Modele gitmez.

### 9.1 LeanTools: araç tanımları kısaltılır

Araç tanımları **her turda** gider. Ölçümde bir koşunun %40'ı bunlardı
(22 turda 49.478 token, tur başına ~2.250). `LeanTools.lean` iki şey yapar:

- açıklamayı ilk cümlelerine indirir (`describe-cap` = 220 karakter, cümle sonunda keser);
- şemadan yalnız **düzyazıyı** atar: `description`, `examples`, `title`,
  `default`, `$schema`… `type`, `properties` (argüman **adları**), `required`,
  `enum` ve `items` olduğu gibi kalır.

Argüman adı "description" olsa bile silinmez. `properties`'in **anahtarlarına**
değil değerlerine inilir. Ekran Keşfi her kelimeyi görmeye devam eder.

---

## 10. BudgetedTool: her çağrının geçtiği kapı

**Dosya:** `BudgetedTool.java`. Modelin her araç çağrısı, `do_steps`'in
içindeki alt adımlar dahil, `guarded()` metodundan geçer. Kural şu:
**reddetme bir CÜMLEDİR, istisna değil.** Fırlatılan bir hata modele
"başarısız araç" olarak döner ve model onu tekrar dener.

### 10.1 Bir çağrının yolculuğu

```
model: tool_call(name, args)
   │
   ▼
guarded(input)
   │ trace(input)          log: "[uptest] explore -> <araç> <argümanlar>"
   │ tick(input)           (Ekran Keşfi: ZORUNLU listesinden düş)
   │
   ├─ refuse(input) ── null değilse ──► bant: REFUSED, policy.missed(), CÜMLE modele döner
   │
   ├─ invoke:  BusyPage.waitOut  (overlay "intercepts pointer events" ise ≤6 deneme, ~30 sn)
   │             └─ report_result ise: RunVerdict.acceptJson(input)  (esnek okuma)
   │             └─ değilse: MCP aracı
   │
   ├─ watch(result)        "has been closed" / "no open pages" / about:blank → aborted
   │
   ├─ HATA (fırlatıldı):   bant: ERROR, policy.outcome(threw)
   │                       Select2 göstergesine yazma  → select2DisplayHint ile CEVAP
   │                       yanlış target               → targetHint ile CEVAP
   │                       diğer                       → fırlat (model hatayı görür)
   │
   └─ BAŞARI:
        answer = trim(result)            §13: unwrap → çerçeve → LeanResults (≤12.000)
        bant:   OK, TAM sonuç            (kesilmemiş; Gherkin oluştur onu okur)
        trace:  tam sonuç + modelin gördüğü (answer)
        policy.outcome(...)              başarılı ACTING → hata hafızası ve miss sayacı sıfırlanır
        policy.after(...)                ACTING ise: bekle, overlay bekle, snapshot, ekle; bütçe notu
        ──► modele döner
```

### 10.2 Reddetme sırası (`refuse`)

Sıra kodda sabittir ve testlerle sabitlenmiştir. Örneğin bütçe kontrolü
`report_result`'tan **önce** olsaydı, bütçesi biten koşu sonuç bile
bildiremezdi.

```
 1. aborted (tarayıcı kapandı)            → CLOSED / BrowserClosed   (her şeyden önce)
 2. sink kapalı (yalnız Ekran Keşfi)      → FINISHED
 3. verdict kapalı (sonuç bildirildi)     → REPORTED
 4. araç == report_result                 → GEÇ  ◄── aşağıdaki hiçbir bekçi sonucu susturamaz
 5. token bütçesi doldu                   → SPENT
 6. takıldı (4 eylem üst üste olmadı)     → STUCK
 7. TIKLAMA: listesinde                   → FORBIDDEN
 8. F5 / Ctrl+R / Cmd+R                   → NO_RELOAD
 9. senaryoda geçmeyen onay sözcüğü       → UNASKED_COMMIT
10. talimatta geçmeyen alan (Ekran Keşfi) → UNMENTIONED
11. ZORUNLU adım atlanmış (Ekran Keşfi)   → SKIPPED_STEP
12. ikinci navigate                       → ALREADY_THERE
13. aynı başarısız çağrı, sayfa aynı      → SAME_FAILURE
14. araç çağrı tavanı (40) bitti          → EXHAUSTED
```

### 10.3 Ret cümleleri (modelin gördüğü)

| Ret | Ne zaman | Modele giden metin (özet) |
|---|---|---|
| `SPENT` | Kullanılan token ≥ 100.000 | "TOKEN BUDGET SPENT … Call report_result NOW — … steps you did carry out in succeeded, every step you did not reach in failed." |
| `STUCK` | 4 eylem üst üste başarısız/reddedildi | "STOP. Your last actions all failed or were refused one after another … Call report_result NOW" |
| `UNASKED_COMMIT` | Tıklanan şeyin adı ya da argüman onay sözcüğü taşıyor, senaryo o sözcüğü içermiyor | "REFUSED: "%s" commits something and the scenario never asked for it. This is a bank …" |
| `NO_RELOAD` | `browser_press_key` F5 vb. | "REFUSED: reloading the page is not allowed. It throws away every step …" |
| `SAME_FAILURE` | Aynı (araç, argüman) aynı sayfada zaten hata aldı | "REFUSED: you already made exactly this call and nothing has changed … The error it got was: …" |
| `ALREADY_THERE` | İkinci navigate | "This flow is walked with the MENUS, not by typing addresses" |
| `REPORTED` | report_result'tan sonra | "The result has already been reported and this task is over." |
| `EXHAUSTED` | 40 çağrı | "Tool budget exhausted. Do not call any more tools." |
| `FORBIDDEN` | Senaryoda `TIKLAMA: A, B` satırı ve argüman A'yı içeriyor | "The instruction explicitly said NOT to touch this control" |

### 10.4 Onay engeli (`unaskedCommit`) ayrıntısı

```
onay sözcükleri  : onayla, onay ver, evet, gönder, kaydet, sil, tamam,
                   approve, confirm, submit, delete, yes
uygulandığı araç : browser_click, browser_handle_dialog, browser_press_key
yalnız           : "AI ile çalıştır" (policy != null)

her sözcük için:
   talimat (katlanmış) bu sözcüğü İÇERİYORSA  → bu sözcük serbest
   değilse:
      argümanlar sözcüğü içeriyor             → RET
      ya da TargetCaption.of(tape, input)      → tıklanan ref'in SAYFADAKİ adı
            sözcüğü TAM KELİME olarak içeriyor → RET
```

`TargetCaption` neden var: bu yolda tıklamalar `{"target":"f3e703"}` diye
gelir ve argümanlarda hiç kelime bulunmaz. Ref'in ne olduğu, bantta o ref'i
taşıyan en son sayfadan okunur. Sayfa metninde "sil" kelimesi
"Silinmiş Faturalar"ın da içinde geçer, bu yüzden orada **tam kelime**
aranır.

### 10.5 Hata cevabına eklenen ipuçları

| Hata | İpucu | Neden |
|---|---|---|
| `select2-selection__rendered` + `not editable` | "THIS IS THE DROPDOWN'S DISPLAY, NOT A TEXT BOX … browser_press_sequentially with just the text needs no target" | Ajan Select2'nin gösterge kutusuna üç kez yazmaya çalışmıştı |
| `does not match any elements` / `while parsing css selector` | "THE TARGET IS WRONG. target must be a reference … like e42, or a real CSS selector" | Ajan `textbox "Seçiniz"` diye hedef uydurup beşer kez denemişti |

---

## 11. AiRunPolicy: sayfa, bütçe, tekrar, takılma

**Dosya:** `AiRunPolicy.java`. Yalnız bu yolda vardır. Öteki yollarda `null`
geçer.

| Görev | Nasıl | Sabit |
|---|---|---|
| **Eylemden sonra sayfa** | `ACTING` bir araç başarıyla dönünce: `settle` kadar bekle → `BusyPage.waitWhileBusy` → ham `browser_snapshot` → banta ayrı kayıt → cevaba `### Sayfa (işlemden sonra, kendiliğinden alındı)` + özet | `settle-ms` 1500 |
| **Bütçe notu** | ≥%80: "[Token bütçesinin %N'i kullanıldı … report_result ile sonuçlan.]"; ≥%100: "[TOKEN BÜTÇESİ AŞILDI …]". Not her sonuçta tekrarlanır, çünkü eskileri kısaltılır | `token-budget` 100.000 |
| **Bütçe duvarı** | `spentOut()` → BudgetedTool `SPENT` döner (report_result hariç) | aynı |
| **Aynı hata** | `(araç, boşluğu sadeleşmiş argüman) → hata` haritası. Başarılı bir `ACTING` çağrı haritayı temizler | — |
| **Takılma** | Üst üste olmayan (hata ya da ret) `ACTING` sayısı. Snapshot ne sayar ne sıfırlar | `STUCK_AFTER` = 4 |
| **Çerçeve** | `PageChrome` ([§13.2](05-sayfa-ve-baglam.md)) | — |
| **Overlay sondası** | Ham `browser_evaluate` (yalnız kod kullanır) | — |

> Not: sınıfın baştaki javadoc'u hâlâ "bütçe bir hedeftir, duvar değil
> (11.11.2026)" diyor. Kod artık durduruyor (`f063a4b`): bir koşu 800.000
> token harcayıp giriş bile yapamamıştı. Geçerli olan koddur.

---

## 12. BusyPage: yükleme katmanı ve F5

**Ölçüldü (12.11.2026, ai-run 01604939):** "ASAŞ ALÜMİNYUM SAN." seçilince
ekran faturaları tam sayfa bir katmanın altında yüklüyor. VPN üzerinden bu
yükleme, MCP'nin bir tıklamaya verdiği 5 saniyeden uzun sürüyor:

```
- <div class="overlay-full nonprintable"></div> intercepts pointer events
TimeoutError: browserBackend.callTool: Timeout 5000ms exceeded.
```

Deterministik koşu aynı ekrandan geçiyor, çünkü Playwright'ın varsayılan
beklemesi 30 saniye. Ajan ise önce Escape'e, sonra iki kez F5'e bastı. İkinci
yenileme `about:blank`'te kaldı ve koşu sonuçsuz bitti.

```
            ┌──────────── BusyPage ─────────────────────────────────────────────┐
çağrı ────► │ waitOut: sonuç/hata "intercepts pointer events" ise tekrar dene    │
            │          ≤ ATTEMPTS = 6  (~6 × 5 sn = Playwright'ın 30 sn'si)       │
            │          (tık hiç olmadığı için tekrarlamak iki kez yapmaz)         │
            ├────────────────────────────────────────────────────────────────────┤
sayfa ────► │ waitWhileBusy (okumadan ÖNCE, prelude'da ve her eylemden sonra):   │
okunmadan   │   PROBE (JS, browser_evaluate ile):                                │
önce        │     ekranın ortasındaki öğe position fixed/absolute,               │
            │     ÇOCUĞU YOK, genişlik ve yükseklik ≥ %90 → BUSY                 │
            │   ≤ POLLS = 20 × settle (~30 sn); sonra sayfa yine de okunur        │
            │   evaluate aracı yoksa / şemasında "function" yoksa → atlanır      │
            ├────────────────────────────────────────────────────────────────────┤
F5 ───────► │ reload: browser_press_key ∈ {F5, Ctrl+R, Ctrl+F5, Shift+F5,         │
            │         Meta+R, Ctrl+Shift+R, BrowserRefresh …} → NO_RELOAD         │
            └────────────────────────────────────────────────────────────────────┘
```

Sonda bilerek sınıf adı aramaz. Aradığı şey bir **şekildir**: ekranı örten,
içi boş bir öğe. Sayfayı tutan bir yerleşim sarmalayıcısının her zaman
çocuğu vardır.
