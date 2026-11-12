# AI ile çalıştır — Bilinen açıklar ve dosya haritası

[← İçindekiler](README.md) · [← Hata ayıklama](11-hata-ayiklama.md) · [Örnek koşu: 2628c4fd →](13-ornek-kosu-2628c4fd.md)

---

## 26. Bilinen açıklar ve açık işler

Koddan okunanlar ve ölçülenler ayrı işaretlendi.

| # | Açık | Kaynak | Etkisi | Durum |
|---|---|---|---|---|
| 1 | **Onay engeli tüm ilk kullanıcı mesajına bakıyor, yalnız senaryoya değil.** `toolsFor(null, userTurn, …)`: kapının `instruction`'ı ilk sayfanın özetini de içerir ve sözcükler **alt dize** olarak aranır (`instruction.contains("sil")`). İlk ekranda "Silinmiş…", "Gönderilen…", "Tamam" gibi bir metin varsa o onay sözcüğü serbest kalır | koddan okundu, koşuyla doğrulanmadı | Senaryonun istemediği bir onay geçebilir | açık |
| 2 | `report_result`'ın bütün JSON'u `succeeded` string'inin içinde gelirse `failed` kaybolabilir | önceki oturumda gözlendi | Yanlış "geçti" riski (ClaimCheck yalnız tıklamaları yakalar) | düzeltme hazırlandı, istek üzerine geri alındı |
| 3 | Liste kısaltma (>15) senaryoda adı **tam yazılmamış** seçeneği gizler | ölçüldü (a73ead34'te liste kısaltılmıştı, ama iyi yazılmış senaryo aynı listeyle geçti) | Ajan aramaya mecbur kalır | senaryo kuralı 1 ile yönetiliyor |
| 4 | ClaimCheck seçimleri ve BEKLENEN'i doğrulamaz | tasarım | "Seçtim" ya da "gördüm" iddiası kontrolsüz | açık |
| 5 | `AiRunPolicy` javadoc'u "bütçe duvar değil" diyor, kod duvar | koddan okundu | Yalnız belge tutarsızlığı | açık |
| 6 | PageChrome yalnız `ON_SCREEN`'de öğrenir. Ekran başlığı menüdeki adla aynı değilse `NONE` olur | tasarım | Çerçeve her turda gider (pahalı ama güvenli) | bilinçli |
| 7 | `UPTEST_MCP_CONFIG` (makineye göre `.mcp.json`) | git stash'te bekliyor | Mac `.mcp.json`'u elle düzenliyor | önerildi |

---

## 27. Dosya haritası

```
uptest-orchestrator/src/main/
├── frontend/src/
│   ├── product/ScenarioDetail.tsx          düğme + AiSummaryView (AI ÖZET)
│   ├── workspace/side/ProductPanel.tsx     aiRun: metin, istek; generateGherkin; LOGIN_URL, TEST_SMS_CODE
│   ├── lib/scenarioProse.ts                docAsText: senaryo → numaralı düz metin
│   ├── api/types.ts                        ExploreRequest/Answer, AiRunSummary
│   └── tokens/                             Token Takibi paneli
├── resources/application.properties        uptest.explore.* (§22)
└── java/orchestrator/
    ├── explore/
    │   ├── ExploreController.java          POST /explore: compose, runScenario, toolsFor, closeBrowser
    │   ├── AiRunPrelude.java               giriş + menü + yükleme bekleme kodla; diagnose; withoutGoTo
    │   ├── AgentPrompts.java               RUN_SYSTEM, RESULT_RULES (+ Ekran Keşfi'nin SYSTEM vb.)
    │   ├── BudgetedTool.java               kapı: ret zinciri, onay engeli, ipuçları, trim, bant kaydı
    │   ├── AiRunPolicy.java                eylem-sonrası sayfa, bütçe, aynı hata, takılma
    │   ├── BusyPage.java                   overlay bekleme, F5 engeli
    │   ├── CompactingAdvisor.java          eski tool sonuçlarını kısaltan advisor
    │   ├── LeanResults.java                sayfa özeti: unwrap, iskelet, gürültü, liste, sınır
    │   ├── LeanTools.java                  araç tanımlarını kısaltma
    │   ├── PageChrome.java                 bankanın çerçevesini öğren ve at
    │   ├── StepRunner.java                 do_steps
    │   ├── DateTool.java                   set_date
    │   ├── TargetCaption.java              ref → sayfadaki ad (onay engeli için)
    │   ├── RunVerdict.java                 report_result + karar
    │   ├── ClaimCheck.java                 iddia ↔ bant
    │   ├── ActionTape.java / TapeFile.java bant yazma / okuma
    │   ├── TapeRenderer.java (+PlaywrightLine, TapeScope, ExpectedVerifies, AriaSnapshot)
    │   │                                   bant → Gherkin taslağı
    │   ├── TapeEvidence.java               bandın modele okunur özeti; Playwright satırı
    │   ├── TapeGherkinController.java      POST /explore/gherkin
    │   └── Provenance.java                 Gherkin'deki tırnaklı metin uydurma mı
    └── aitrace/                            AiTraces, TraceSession, … (Token Takibi)

.mcp.json                                   MCP sunucusu (VDI yolu; Mac'te yerel kopya)
runs/mcp/<tapeId>/tape.json                 bantlar (gitignore)
runs/llm/<id>/                              trace tam metinleri (gitignore)
```

Testler: `uptest-orchestrator/src/test/java/orchestrator/explore/`
(`AiRunPreludeTest`, `AiRunPolicyTest`, `BusyPageTest`, `PageChromeTest`,
`StepRunnerTest`, `LeanResultsTest`, `LeanToolsTest`, `RunVerdictTest`,
`ClaimCheckTest`, `CompactingAdvisorTest`, `RepeatedFailureTest`,
`Select2DisplayHintTest`, `TargetHintTest`, `TapeRenderer*Test`).
Bu testler tarayıcısız saf Java'dır ve yerelde koşar. Tarayıcı gerektiren her
şeyi yalnız gerçek bir koşu doğrular.
