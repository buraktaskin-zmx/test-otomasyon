# AI ile çalıştır — Genel bakış

[← İçindekiler](README.md) · [İstek ve prelude →](02-istek-ve-prelude.md)

---

## 1. Tek paragrafta

"AI ile çalıştır" kayıtlı bir senaryoyu **Gherkin'e çevirmeden**, düz Türkçe
haliyle bir LLM ajanına verir. Ajan gerçek bir tarayıcıyı (Playwright MCP
üzerinden Edge) adım adım sürer ve sonunda yapılandırılmış bir sonuç bildirir:
doğru ilerleyen adımlar, geçemeyen adımlar, uyarılar, tek bir karar. Ajan her
şeyi yapmaz. **Giriş ve menü kodla yapılır** (`AiRunPrelude`). Ajanın her
çağrısı bir **kapıdan** geçer (`BudgetedTool`): bütçe, onay engeli, F5 engeli,
aynı hatayı tekrar engeli. Her eylemden sonra **sayfa kodla okunup** cevaba
eklenir (`AiRunPolicy`). Sayfa modele **özetlenerek** gider (`LeanResults`,
`PageChrome`). Eski sayfalar **konuşmadan çıkarılır** (`CompactingAdvisor`).
**Karar da ajandan alınmaz.** Kod onu bantla karşılaştırır ve gerekirse çevirir
(`RunVerdict`, `ClaimCheck`). Her çağrı tam haliyle diske yazılır
(`ActionTape`). Bu bant sonradan "Gherkin oluştur" ile deterministik bir
senaryoya çevrilebilir.

---

## 2. Süreçler: kim nerede çalışıyor

Bir "AI ile çalıştır" koşusunda **beş ayrı süreç** konuşur. Hata
ayıklamadaki ilk soru hep "hangisi bozuldu"dur.

```
┌──────────────────────────┐   HTTP (JSON)    ┌───────────────────────────────────────────┐
│ 1. Tarayıcı (analist)    │ ───────────────► │ 2. uptest-orchestrator  (:8035, Java)     │
│    React UI  /ui         │ ◄─────────────── │    Spring Boot + Spring AI ChatClient     │
│    ProductPanel.tsx      │  POST /api/v1/   │    orchestrator/explore/*                 │
└──────────────────────────┘  explore         │    orchestrator/aitrace/* (Token Takibi)  │
                                              └───────┬──────────────────────┬────────────┘
                                                      │ OpenAI uyumlu HTTP   │ stdio (MCP)
                                                      │ /v1/chat/completions │ .mcp.json
                                                      ▼                      ▼
                                   ┌──────────────────────────┐   ┌──────────────────────────┐
                                   │ 3. vLLM — Qwen modeli    │   │ 4. Playwright MCP sunucu │
                                   │   (tool calling)         │   │    node, @playwright/mcp │
                                   │   bağlam 131.072 token   │   │    0.0.82 (SABİT)        │
                                   └──────────────────────────┘   │    C:/tools/playwright-  │
                                                                  │    mcp/... --msedge      │
                                                                  │    --isolated            │
                                                                  └────────────┬─────────────┘
                                                                               │ CDP
                                                                               ▼
                                                                  ┌──────────────────────────┐
                                                                  │ 5. Edge (görünür)        │
                                                                  │  ifctest / intest        │
                                                                  │  (bankanın test ortamı)  │
                                                                  └──────────────────────────┘
```

| Süreç | Ne iş yapar | Hangi koşulda kendisi "bozuk" sayılır |
|---|---|---|
| React UI | Senaryoyu metne çevirir, isteği atar, sonucu gösterir | Eski build sunuluyorsa (`npm run build` unutulduysa) |
| Orchestrator | Prelude, kapı, sayfa okuma, özetleme, karar, bant | 502 + yığın izi sunucu logunda |
| vLLM / Qwen | Bir sonraki aracı seçer, sonucu bildirir | Model çağrısı 5xx / zaman aşımı |
| Playwright MCP | Araç çağrılarını Playwright'a çevirir | `0.0.85` ile `ERR_CONNECTION_RESET` (sürüm 0.0.82'ye sabit) |
| Edge + banka | Gerçek ekran | Ağ (Mac'te VPN), yavaş yükleme katmanı |

**Deterministik koşuyla farkı:** "Çalıştır" (Gherkin) yolu
`uptest-executor`'a (:9035) gider ve Cucumber + Playwright Java ile çalışır,
model yoktur. "AI ile çalıştır" executor'a **hiç gitmez**. Tarayıcıyı
orchestrator, MCP üzerinden sürer.

```
                 ┌─────────────── Kayıtlı Senaryolar ───────────────┐
                 │                                                  │
        [Çalıştır]                                        [AI ile çalıştır]
                 │                                                  │
   Gherkin ─► executor :9035                       düz Türkçe ─► orchestrator :8035
   Cucumber + Playwright (Java)                    Spring AI ajanı + Playwright MCP
   model YOK, adım sözlüğü                         model VAR, araç çağrıları
   sonuç: adım adım yeşil/kırmızı                  sonuç: report_result + kod kontrolü
   kayıt: TEST_RUN (Geçmiş)                        kayıt: tape.json + AI_SESSION (Token Takibi)
```

---

## 3. Uçtan uca akış

```
Analist          ProductPanel        ExploreController        AiRunPrelude       Model (Qwen)        BudgetedTool / MCP / Edge
   │                  │                      │                      │                  │                       │
   │ AI ile çalıştır  │                      │                      │                  │                       │
   ├─────────────────►│ metin = "... ekranına git.\nSonra şu senaryoyu uygula:\n\n" + docAsText(doc)
   │                  │ POST /api/v1/explore {text,url,cif,userId,password,smsCode,mainMenu,
   │                  │                       expected,source:"ai-run",app,product,flow,scenarioPath,screen}
   │                  ├─────────────────────►│
   │                  │                      │ compose() → giriş önsözü + metin
   │                  │                      │ ActionTape.open()  → runs/mcp/<tapeId>/tape.json
   │                  │                      │ AiTraces.open("ai-run") → Token Takibi oturumu
   │                  │                      │ new RunVerdict()
   │                  │                      │ runScenario():
   │                  │                      ├─────────────────────►│ navigate (≤5 deneme)
   │                  │                      │                      │ #userCode/#userId/#password yaz
   │                  │                      │                      │ #btnSubmit, (#otpPassword → #btnSubmit)
   │                  │                      │                      │ menü: i:text-is(ANA) → .main-nav a (≤3)
   │                  │                      │                      │ loaded(): ≤20 bakış, "Lütfen bekleyiniz" gitsin
   │                  │                      │◄─────────────────────┤ Outcome{ON_SCREEN, sayfa}
   │                  │                      │ NOTHING ve cif var → "Ajan başlatılmadı" (502), TOKEN HARCANMAZ
   │                  │                      │ PageChrome.forRun()  → çerçeve ref'leri
   │                  │                      │ instruction()        → ilk kullanıcı mesajı (+ <<EKRAN>>)
   │                  │                      │ client.prompt().system(RUN_SYSTEM+RESULT_RULES)
   │                  │                      │   .user(ilk mesaj).advisors(CompactingAdvisor)
   │                  │                      │   .tools(11 araç, hepsi BudgetedTool sarmalı)
   │                  │                      ├──────────────────────────────────────────►│
   │                  │                      │                 ┌────── TUR DÖNGÜSÜ (Spring AI ToolCallingAdvisor) ─────┐
   │                  │                      │                 │ CompactingAdvisor: eski sonuçları kısalt             │
   │                  │                      │                 │ model → araç çağrısı (ör. do_steps)                  │
   │                  │                      │                 │ BudgetedTool.guarded: reddet? → MCP → bant → özet    │
   │                  │                      │                 │ AiRunPolicy.after: bekle, overlay, snapshot ekle     │
   │                  │                      │                 │ ... model report_result çağırana kadar               │
   │                  │                      │                 └──────────────────────────────────────────────────────┘
   │                  │                      │◄──────────────────────────────────────────┤ report_result (returnDirect)
   │                  │                      │ uyarılar: prelude notu, bütçe aşımı
   │                  │                      │ TapeRenderer.render(bant) → Gherkin taslağı
   │                  │                      │ ClaimCheck.apply()        → yalan "yaptım"ı GEÇMEDİ'ye çevir
   │                  │                      │ finally: tape.finish(), browser_close, trace.close()
   │                  │◄─────────────────────┤ 200 {answer, summary, tapeId, gherkin, notes, traceId}
   │◄─────────────────┤ AI ÖZET: ✓/✗ + listeler, [Gherkin oluştur] düğmesi
   │                  │
   │ Gherkin oluştur  │ POST /api/v1/explore/gherkin {tapeId, app, flow, mainMenu, scenario, expected}
   │                  │   → bant + katalog + sözlük ile ayrı bir model çağrısı → Provenance kontrolü
```
