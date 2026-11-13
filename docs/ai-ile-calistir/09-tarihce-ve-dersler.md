# AI ile çalıştır — Tarihçe ve dersler

[← İçindekiler](README.md) · [← Ayarlar ve sabitler](08-ayarlar.md) · [Senaryo yazım rehberi →](10-senaryo-yazim-rehberi.md)

---

## 23. Maliyet ve değişiklik tarihçesi

### 23.1 Ölçümler

| Tarih | Koşu | Tur | Girdi token | Sonuç | Ne öğretti |
|---|---|---|---|---|---|
| 11.11 | firma-fatura … salt-okunur (eski yol, `SYSTEM`) | 52 | 1.478.146 | bitti | Maliyet = tur × görülen her şey. %66 tool sonucu, yarısı bekleme/arama turu |
| 11.11 | aynı, eb0ba99 sonrası | 22 | 124.817 | bitti | Tool sonuçları %66 → %26. En büyük kalem araç tanımları: %40 |
| 11.11 | — | — | 800.000 | giriş yok | Uyaran bütçe sınır değildir → duvar (f063a4b) |
| 12.11 | "Firma Seçimi listesinde arama" | 26 | 112.547 | bitmedi | `page-cap` 6.000 arama kutusunu gizledi, ajan hedef uydurdu |
| 12.11 | 40a2a7f denemesi | — | — | körleşti | Sayfa düzleştirme menü bağlantısını kesti → geri alındı |
| 12.11 | bant 2628c4fd | — | — | yalan "geçti" | Ajan "Geri'ye bastım" dedi, bantta yok → ClaimCheck (9961404) |
| 12.11 | (kullanıcının bildirdiği) | — | ~35.000 | geçti | Hedef bölgesi |
| 12.11 | d88e4b14 | 25 | 100.901 | ekran yüklenmedi | "Lütfen bekleyiniz"de ajan başlatılmamalı → e7faad3 |
| 12.11 | 01604939 | — | — | about:blank | Overlay 5 sn'yi aştı, ajan F5'e bastı → BusyPage (7ceccac) |
| 12.11 | a73ead34 | — | — | ASAŞ seçilemedi | Aynı kod, **kötü yazılmış senaryo** → [§24](10-senaryo-yazim-rehberi.md) |
| 13.11 | bant 4bc5c9f0, "fatura oluştur" (17 adım) | 32 | 192.735 | bitmedi | Bütçe duvarından sonra **13 reddedilen tur** (~85.000 token) → duvar artık koşuyu kodla bitirir (`WalkOver`), token sınırı kapatıldı. Ajan `do_steps` kullanmadı → kurallı adımları kod yürütür (`CodeSteps`, [§15.5](06-do-steps-ve-set-date.md)). Takvim yıl listesi gizliydi → oklarla (§16) |

### 23.2 Commit'ler (bu yolu şekillendiren)

| Commit | Ne değişti |
|---|---|
| `be27656` | AI ile çalıştır, Serbest Gözlem ile aynı ajanı çağırıyor |
| `35fc695` → `154240a` | Eylem bandı; bant Gherkin'e render ediliyor |
| `7192b98` | Token Takibi; AI Özet ve tek sonuç (`report_result`) |
| `eb0ba99` | 52 tur → ~10 tur hedefi: prelude, dar araç kutusu, sayfa-sonrası-eylem, CompactingAdvisor, RUN_SYSTEM |
| `ca6e367` | Giriş kutusu beklenir, hatalar loglanır |
| `efd5763` | ClassCastException: kısaltma advisor oldu, ikinci istemci yok |
| `c1c3d10` | LeanTools (%40); report_result şekle takılmıyor |
| `f063a4b` | Token bütçesi tavan; giriş yoksa ajan başlamıyor |
| `6163fea` | Onaysız iptal engeli; tool sonuçları özetleniyor (LeanResults) |
| `40a2a7f` / `f713b14` | Sayfa düzleştirme + ref'li do_steps → **geri alındı** |
| `82008f1` | Hedefsiz yazma (`browser_press_sequentially`); MCP sürümü sabit |
| `eb64738` | Çelişen "ekranına git" satırı kalktı; aynı hatalı çağrı tekrar edilemez |
| `bbe38c4` | İlk adres 5 kez; "proxy" tahmini kalktı |
| `9961404` | ClaimCheck; `unwrap` (gerçek MCP biçimi); `page-cap` 12.000; onay engeli ref'in adını okuyor |
| `45d98d9` | Gürültü temizliği (tidy), PageChrome, ada göre do_steps |
| `e7faad3` | Yüklenmeyen ekranda ajan başlamaz; takılma bekçisi |
| `7ceccac` | BusyPage: overlay beklenir, F5 reddedilir |

### 23.3 Ayrıntılı ölçüm notları (Token Takibi belgesinin eski §8'i)

> 12.11.2026'ya kadar `docs/token-takibi/README.md` §8 olarak duran metin, olduğu gibi.
> İçindeki "tablo satırı N" atıfları aşağıdaki tabloya, "§3b" Token Takibi belgesine aittir.


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
| 13 | Senaryonun son adımı görevin sonu: BEKLENEN görülünce hemen `report_result`; tarayıcıyı ajan kapatmaz (kod kapatıyor) | `AgentPrompts.RESULT_RULES` |
| 14 | İlk adres **geçici ağ hatasında 5 kez** denenir — Mac→ifctest bağlantıların ~5'te 2'sini kesiyor (curl ile ölçüldü). Kesilen giriş token harcamaz ama koşuyu bitiriyordu | `AiRunPrelude.navigate`; ayrıntı `MCP-TARAYICI-SORUNU.md` |

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

#### 12.11.2026: hâlâ pahalı — ve denenip geri alınan çözüm

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


---

## 28. Tasarım ilkeleri (dersler)

Bu yolun her parçası bir ölçümden çıktı. Aynı hataları tekrarlamamak için:

1. **Prompt kuralı ancak kod olunca tutar.** "Onaya basma", "aynı çağrıyı
   tekrarlama", "F5'e basma" önce prompt kuralıydı. Hepsi çiğnendi ve
   hepsi kapıya taşındı ([§8.3](03-promptlar.md)).
2. **Kod yapabiliyorsa turu modelden al.** Giriş, menü, bekleme, sayfayı
   okuma, tarih: her biri bir turdu ve her tur, o ana kadar görülen her şeyi
   yeniden ödetir.
3. **Ölü olanı yeniden gönderme.** Eski sayfaların ref'leri ölüdür
   (CompactingAdvisor). Çerçeve her sayfada aynıdır (PageChrome).
4. **Sayfa özeti gerçek bir koşuda ölçülür, fixture'da değil.** `unwrap`
   yokken sınıf hiçbir şey silmiyordu ve testler geçiyordu. 40a2a7f "her ref
   korunuyor" testini geçti ama ajanı körleştirdi. Ölçüt, **bir sonraki
   eylemin ihtiyaç duyduğu öğenin** özette kalmasıdır.
5. **Reddetme bir cümledir.** Fırlatılan hata tekrar denenir, okunan cümle
   davranışı değiştirir. Ret de ne yapılacağını söyler.
6. **Kararı ajan vermez.** Ajanın "geçti"si bantla karşılaştırılır
   (ClaimCheck). Bir `failed` satırı kararı düşürür. Bütçe ve takılma koşuyu
   bir **sonuçla** bitirir, istisnayla değil.
7. **Ortam bozuksa model başlatma.** Giriş yapılamıyor ya da ekran
   yüklenmiyorsa hiçbir model bunun etrafından dolaşamaz. Token harcamadan,
   sebebi adıyla söyleyerek dur.
8. **Bant kanıttır, konuşma bütçedir.** Model az görür, bant hep tam tutar.
   "Gherkin oluştur", hata ayıklama ve ClaimCheck bantı okur.
9. **Senaryonun yazımı da mimarinin parçasıdır.** Kodun senaryoyu anladığı
   tek yer tırnaklı adlardır ([§24](10-senaryo-yazim-rehberi.md)).
