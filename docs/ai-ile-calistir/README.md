# AI ile çalıştır

Kayıtlı Senaryolar'da bir senaryonun yanındaki **"AI ile çalıştır"**
düğmesine basıldığında ne olduğunu anlatan belge seti:
- hangi süreç neyi çalıştırır;
- modele tam olarak ne gider;
- hangi kararlar modelden alınıp koda verildi;
- sonuç nasıl hesaplanır;
- token nereye gider;
- bir koşu bozulduğunda nereye bakılır.

Kaynak: `7ceccac` itibarıyla kod (`uptest-orchestrator/src/main/java/orchestrator/explore/`,
`frontend/src/workspace/side/ProductPanel.tsx`, `frontend/src/lib/scenarioProse.ts`)
ve 11–12.11.2026 tarihli gerçek koşuların ölçümleri. Ölçülmüş olanla koddan
okunmuş olan ayrı yazıldı; "ölçüldü" diyen her cümlenin arkasında bir bant
(`tapeId`) ya da AI oturumu vardır.

> İlgili belgeler: Token Takibi paneli `docs/token-takibi/README.md`,
> Dashboard `docs/dashboard/README.md`, MCP sürümü ve Mac ağı
> `docs/MCP-TARAYICI-SORUNU.md`, bant biçimi `docs/EYLEM-BANDI.md`.

---

## Tek paragrafta

"AI ile çalıştır" kayıtlı bir senaryoyu **Gherkin'e çevirmeden**, düz Türkçe
haliyle bir LLM ajanına verir. Ajan gerçek bir tarayıcıyı (Playwright MCP
üzerinden Edge) adım adım sürer ve sonunda yapılandırılmış bir sonuç bildirir.

Ajan her şeyi kendi yapmaz:
- **Giriş ve menüyü kod yapar.**
- Her çağrısı bir **kapıdan** geçer: bütçe, onay engeli, F5 engeli, tekrar engeli.
- Her eylemden sonra **sayfayı kod okur**. Model sayfayı **özetlenmiş** görür,
  eski sayfalar konuşmadan **çıkarılır**.
- **Kararı ajan vermez:** kod, ajanın iddiasını bantla karşılaştırır.
- Her çağrı tam haliyle diske yazılır. Bu bant sonradan "Gherkin oluştur" ile
  deterministik bir senaryoya çevrilebilir.
- Bir kez **geçen** senaryonun adımları saklanır (`AI_RUN_CACHE`). Sonraki
  koşuda bu adımları kod uygular, sonucu tek bir AI turu değerlendirir. Bir
  adım tutmazsa ajan o noktadan devralır ([§29](14-adim-cache.md)).

```
 analist ──► React UI ──► orchestrator ──► AiRunPrelude (KOD: giriş + menü + yükleme bekleme)
                               │
                               ├──► Qwen (vLLM)  ◄─── her turda: system + araçlar + kısaltılmış geçmiş + güncel sayfa
                               │        │ araç çağrısı
                               │        ▼
                               ├──► BudgetedTool (kapı) ──► Playwright MCP ──► Edge ──► banka test ortamı
                               │        │ tam sonuç
                               │        ▼
                               │    tape.json (kanıt)  ·  Token Takibi (maliyet)
                               │
                               └──► report_result ──► RunVerdict + ClaimCheck ──► AI ÖZET (✓ Geçti / ✗ Geçmedi)
```

---

## Kim neyi okumalı

| Okur | Başla | Sonra |
|---|---|---|
| **Senaryo yazan analist** | [10 — Senaryo yazım rehberi](10-senaryo-yazim-rehberi.md) | [13 — Örnek koşu](13-ornek-kosu-2628c4fd.md) |
| **Bozulan bir koşuyu inceleyen** | [11 — Hata ayıklama](11-hata-ayiklama.md) | [12 — Bilinen açıklar](12-bilinen-aciklar.md), [04 — Araçlar ve kapı](04-araclar-ve-kapi.md) |
| **Kodu değiştirecek geliştirici** | [01 — Genel bakış](01-genel-bakis.md), sonra sırayla 02 → 09 | [09 — Tarihçe ve dersler](09-tarihce-ve-dersler.md) (aynı hatayı tekrarlamamak için) |
| **Token / maliyet soran** | [05 — Sayfa ve bağlam](05-sayfa-ve-baglam.md) | [09 — Tarihçe](09-tarihce-ve-dersler.md), `docs/token-takibi/README.md` |

---

## İçindekiler

| Dosya | Bölümler | İçerik |
|---|---|---|
| [01-genel-bakis.md](01-genel-bakis.md) | §1–§3 | Tek paragraf, beş süreç ve deterministik koşuyla farkı, uçtan uca sıra diyagramı |
| [02-istek-ve-prelude.md](02-istek-ve-prelude.md) | §4–§7 | UI'nin gönderdiği metin ve gövde, `ExploreController`, giriş/menü/yükleme kodla (`AiRunPrelude`), ajana giden ilk mesaj |
| [03-promptlar.md](03-promptlar.md) | §8 | `RUN_SYSTEM` + `RESULT_RULES` tam metin; hangi prompt kuralının kodla zorlandığı |
| [04-araclar-ve-kapi.md](04-araclar-ve-kapi.md) | §9–§12 | 11 araç, `LeanTools`, `BudgetedTool` ret zinciri ve cümleleri, onay engeli, `AiRunPolicy`, `BusyPage` |
| [05-sayfa-ve-baglam.md](05-sayfa-ve-baglam.md) | §13–§14 | Sayfanın modele yolculuğu (`LeanResults`, `PageChrome`), `CompactingAdvisor` tur tur |
| [06-do-steps-ve-set-date.md](06-do-steps-ve-set-date.md) | §15–§16 | Birkaç adım tek turda: şema, eylemler, durma koşulları; tarih aracı |
| [07-sonuc-kayit-gherkin.md](07-sonuc-kayit-gherkin.md) | §17–§21 | `report_result`, karar hesabı, `ClaimCheck`, HTTP cevabı ve AI ÖZET, `tape.json`, Gherkin oluştur, Token Takibi |
| [08-ayarlar.md](08-ayarlar.md) | §22 | `application.properties`, `.mcp.json`, kod sabitleri |
| [09-tarihce-ve-dersler.md](09-tarihce-ve-dersler.md) | §23, §28 | Ölçümler (52 tur / 1,48 mn token'dan bugüne), commit'ler, tasarım ilkeleri |
| [10-senaryo-yazim-rehberi.md](10-senaryo-yazim-rehberi.md) | §24 | 7 kural, kontrol listesi, öğe türü sözlüğü, onay sözcükleri, kötü→iyi örnekler |
| [11-hata-ayiklama.md](11-hata-ayiklama.md) | §25 | Log satırları, belirti tablosu, bakış sırası, kopyala-yapıştır komutlar |
| [12-bilinen-aciklar.md](12-bilinen-aciklar.md) | §26–§27 | Açık sorunlar, dosya haritası |
| [13-ornek-kosu-2628c4fd.md](13-ornek-kosu-2628c4fd.md) | — | Yanlış "Geçti" veren gerçek bir koşu, kayıt kayıt; bugünkü kod ne yapardı |
| [14-adim-cache.md](14-adim-cache.md) | §29–§30 | Adım cache'i (geçen yürüyüş kodla tekrar oynatılır, tek AI turu karar verir, durursa ajan devralır) ve adım içi Beklenen |

**§ numaraları dosyalar arasında sabittir.** Bir metin "§13" diyorsa o bölüm
hangi dosyadaysa oradadır; tablo yukarıda. Bağlantılı olanlar doğrudan o
dosyayı açar.
