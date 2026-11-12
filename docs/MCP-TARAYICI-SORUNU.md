# "AI ile çalıştır" — Mac'te tarayıcı bağlantısı kesiliyordu

**Tarih:** 12.11.2026 · **Durum:** ÇÖZÜLDÜ — Mac'te MCP `0.0.82`'ye sabitlendi,
AI ile çalıştır giriş yapıyor. Mekanizma tam olarak bilinmiyor (aşağıda).

## Kısa özet

- Belirti: AI ile çalıştır girişin ilk adımında `net::ERR_CONNECTION_RESET` alıyordu;
  deterministik koşu aynı Mac'te aynı adrese sorunsuz giriyordu.
- Sebep olarak kanıtlanan tek değişken: **MCP paketinin sürümü.** `npm install`
  ile kurulan en yeni `@playwright/mcp@0.0.85` (Playwright `1.66.0-alpha`)
  kesiliyordu; VDI'daki `0.0.82` (Playwright `1.64.0-alpha`) çalışıyor —
  aynı Chromium binary'si (1208) ile.
- Çözüm: `npm install @playwright/mcp@0.0.82 --save-exact` + `.mcp.json`'da
  `--executable-path` → `chromium-1208`.

## Çalışan ayar (Mac)

| | Değer |
|---|---|
| MCP | `@playwright/mcp@0.0.82` (**sabit**, `--save-exact`) |
| Playwright (MCP'nin) | `1.64.0-alpha-1789764292000` |
| Tarayıcı | `chromium-1208` = Chrome for Testing **145.0.7632.6**, `--executable-path` ile |
| MCP kurulum yeri | `~/Desktop/repo/uplift-test/playwright-mcp` |
| Repo | `~/Desktop/repo/uplift-test/senaryum/test-otomasyon` |
| node | nvm, `v26.13.0` |
| VPN | koşu sırasında **açık** olmalı |

Kurulum:

```bash
cd ~/Desktop/repo/uplift-test/playwright-mcp && npm install @playwright/mcp@0.0.82 --save-exact
grep '"version"' node_modules/@playwright/mcp/package.json node_modules/playwright-core/package.json
# beklenen: 0.0.82 ve 1.64.0-alpha-1789764292000
```

Mac'teki `.mcp.json` (YEREL — commit edilmez; `<kullanıcı>` yerine kendi adın):

```json
{
  "mcpServers": {
    "playwright": {
      "command": "/Users/<kullanıcı>/.nvm/versions/node/v26.13.0/bin/node",
      "args": [
        "/Users/<kullanıcı>/Desktop/repo/uplift-test/playwright-mcp/node_modules/@playwright/mcp/cli.js",
        "--browser", "chromium",
        "--executable-path", "/Users/<kullanıcı>/Library/Caches/ms-playwright/chromium-1208/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
        "--isolated",
        "--allowed-origins", "https://ifctest.yapikredi.com.tr;https://intest.yapikredi.com.tr",
        "--output-dir", "./runs/mcp"
      ]
    }
  }
}
```

Değiştirdikten sonra orchestrator **yeniden başlatılır** — MCP sunucusu yalnız
açılışta başlıyor.

## Kalıcı kurallar

1. **MCP'yi asla yükseltme.** `npm install` / `npm update` kendiliğinden en yeni
   alfaya çıkar; `0.0.85` bu sorunu çıkardı. Sürüm değişecekse bilinçli, tek
   başına ve 5 denemeyle sınanarak değişir.
2. **VDI'yı da sabitle** (önerildi, henüz yapılmadı). VDI'da `0.0.82` kurulu ama
   `package.json`'ı `^0.0.82` diyorsa bir sonraki `npm install` onu da yükseltir:
   ```powershell
   Select-String '"@playwright/mcp"' C:\tools\playwright-mcp\package.json
   # "^0.0.82" görürsen:
   cd C:\tools\playwright-mcp; npm install @playwright/mcp@0.0.82 --save-exact
   ```
3. **`--executable-path` executor'ın Chromium'una bağlı.** `chromium-1208`,
   executor'ın Playwright Java **1.58.0**'ının (`uptest-executor/pom.xml`)
   indirdiği tarayıcı. O sürüm yükseltilirse Playwright eski tarayıcıyı
   önbellekten silebilir; o zaman MCP "executable doesn't exist" der ve bu yol
   güncellenir.
4. **Bizim kod MCP'nin `target` parametresine bağlı.** `AiRunPrelude` (giriş,
   menü) ve `DateTool` elemanı CSS seçiciyle, `target` adıyla gönderiyor.
   Bu ad `0.0.75`'ten itibaren var; daha eski MCP'lerde giriş düşer (tablo aşağıda).
5. **Mac'teki `.mcp.json`'ı commit etme.** Takip edilen dosya VDI'nın Windows
   yollarını taşıyor; Mac'in hâli push edilirse VDI'daki ajan açılmaz.
   `git status`'ta değişmiş görünmesi normal; `git pull` çakışmaz çünkü gelen
   commit'ler o dosyaya dokunmuyor.
6. **`pkill -f "Google Chrome for Testing"` ÇALIŞTIRMA.** MCP sunucusunun kendi
   başlatma satırında `--executable-path ".../Google Chrome for Testing"` geçtiği
   için bu komut tarayıcıyı değil **MCP sunucusunu** öldürür; ardından AI ile
   çalıştır hata vermeden hiçbir şey yapmaz, Token Takibi boş kalır. Olursa:
   orchestrator'ı yeniden başlat. Aynı sebeple `grep "Google Chrome for Testing --"`
   tarayıcıyı değil MCP sunucusunu yakalar.
7. **VPN düşmesini tanı:** `ERR_NAME_NOT_RESOLVED` ile `/tokenize ... I/O error`
   (ya da sağlık kontrolünde `OpenAIIoException`) **aynı anda** görünüyorsa
   sorun MCP değil, Mac o an VPN'den düşmüştür. O denemeyi sayma.

## Makineler

| | Mac — deterministik | Mac — AI (MCP) | VDI — AI (MCP) |
|---|---|---|---|
| Playwright | Java **1.58.0** | `1.64.0-alpha` (önce `1.66.0-alpha`) | `1.64.0-alpha-1789764292000` |
| MCP | — | `0.0.82` (önce `0.0.85`) | `0.0.82` |
| Tarayıcı | Playwright Chromium **1208** | Chromium **1208** (`--executable-path`) | sistem **Edge** (`--browser msedge`) |
| Nasıl seçiliyor | `UPTEST_BROWSER_CHANNEL=` (tanımlı ama **boş** = Playwright'ın kendi Chromium'u) | `.mcp.json` | `.mcp.json` |
| Sonuç | ✅ | ✅ (0.0.82 ile) | ✅ |

Mac'teki Playwright önbelleği (`~/Library/Caches/ms-playwright/`): `chromium-1208`,
`chromium-1254`, `chromium_headless_shell-1208`, `chromium_headless_shell-1254`.

## Denemeler — saat saat (12.11.2026, Mac)

Hangi kodun koştuğu stack trace satır numaralarından okundu: `40a2a7f`'te
`ExploreController.java:595` / `:658`, geri alınmış kodda `:530` / `:588`.

| Saat | Kod | MCP / Playwright | Chromium | Sonuç |
|---|---|---|---|---|
| 09:21 | `40a2a7f` | 0.0.85 / 1.66-alpha | 1254 | ❌ reset |
| 09:22 | `40a2a7f` | 0.0.85 / 1.66-alpha | 1254 | ❌ reset |
| 09:53 | `40a2a7f` | 0.0.85 / 1.66-alpha | 1208 | ✅ giriş + menü (`prelude=ON_SCREEN`); ajan sonra sayfa kesilmesine takıldı (aşağıda) |
| 10:07 | `f713b14` | 0.0.85 / 1.66-alpha | 1208 | ❌ reset |
| 10:14 | `40a2a7f` | 0.0.85 / 1.66-alpha | 1208 | ❌ reset |
| 10:22 | `f713b14` | 0.0.85 / 1.66-alpha | 1208 | ❌ reset |
| 10:29 | `f713b14` | 0.0.56 / 1.58-alpha | 1208 | ✅ adres açıldı — ❌ giriş: `browser_type` `element` + `ref` istedi |
| 10:37 | `f713b14` | 0.0.69 / 1.59-alpha | 1208 | sayılmaz — VPN düştü (`ERR_NAME_NOT_RESOLVED` + `/tokenize` erişilemez) |
| 10:39 | `f713b14` | 0.0.69 / 1.59-alpha | 1208 | ✅ adres açıldı — ❌ giriş: `browser_type` `ref` istedi |
| ~10:45 | `f713b14` | **0.0.82 / 1.64-alpha** | 1208 | ✅ **çalışıyor** (kullanıcı bildirimi; deneme sayısı bildirilmedi) |
| ara | — | curl | — | bir kez `302 / 0.144 s`, bir kez `Connection reset by peer` |
| ara | — | deterministik koşu | 1208 | ✅ her seferinde |

0.0.85 ile toplam: 1 giriş / 6 deneme. 0.0.85'ten sonraki her sürümde (0.0.56,
0.0.69, 0.0.82) adres açıldı.

## Elenen hipotezler — kanıtıyla

| Hipotez | Neden elendi |
|---|---|
| Son commit bozdu | `40a2a7f` aynı sabah hem reset aldı hem giriş yaptı; 10:14'te `40a2a7f`'e dönülünce de reset. Giriş kodu (`AiRunPrelude`) iki commit arasında birebir aynı; hata `runScenario`'nun ilk satırında, değişen kodun hiçbiri o noktaya kadar çalışmıyor. |
| Ağ Playwright'ın kendi Chromium'unu reddediyor | Deterministik koşu da Playwright'ın kendi Chromium'unu (1208) kullanıyor ve giriyor (`UPTEST_BROWSER_CHANNEL=` boş). |
| Chromium revizyonu (1254) | 1208'e geçilince de 0.0.85 ile 1/4'te kaldı. |
| `--allowed-origins` | Playwright 1.66 kaynağında izin dışı istek `route.abort("blockedbyclient")` ile kesiliyor → `ERR_BLOCKED_BY_CLIENT` olur, `ERR_CONNECTION_RESET` değil. |
| "Alfa Playwright sorunlu" | VDI de alfa (1.64) kullanıyor ve çalışıyor. |

## Açık kalanlar

- **Mekanizma bilinmiyor.** 1.66-alpha'nın aynı binary'ye neyi farklı verdiği
  bulunmadı. İki sürümün Chromium varsayılan bayrakları karşılaştırıldı (paketlenmiş
  koddan, sezgisel); ağla ilgili birkaç fark var ama hiçbiri bir TCP reset'ini
  açıklamıyor, o yüzden sebep olarak yazılmadı.
- curl'ün de bir kez reset alması, Mac'ten `ifctest`'e giden yolun tamamen
  kararlı olmadığını gösteriyor olabilir. Tek örnek; ölçülmedi.
- `0.0.82` kendi tarayıcısıyla (`chromium-1246`, `--executable-path` olmadan)
  denenmedi.

## MCP sürüm tablosu

`npm view @playwright/mcp@<sürüm> dependencies` ve paketlerin kaynak kodundan
(`playwright-core/browsers.json`, `elementSchema`) okundu.

| MCP | Playwright | Chromium | Seçici parametresi | Bizim kodla |
|---|---|---|---|---|
| 0.0.55 | 1.58.0-alpha-2026-01-07 | — | — | ❌ |
| 0.0.56 | 1.58.0-alpha-2026-01-16 | 1207 | `element` + `ref` (yalnız snapshot ref'i) | ❌ |
| 0.0.57 – 0.0.60 | 1.59.0-alpha (Ocak) | 1208 | CSS seçici yok | ❌ |
| 0.0.62 – 0.0.64 | 1.59.0-alpha | 1209 | CSS seçici yok | ❌ |
| 0.0.67 – 0.0.68 | 1.59.0-alpha | 1212 | CSS seçici yok | ❌ |
| 0.0.69 | 1.59.0-alpha-1774912654000 | 1217 | `ref` (seçici kabul eder) + `selector?` | ❌ (biz `target` gönderiyoruz) |
| 0.0.75 | 1.61.0-alpha-1778188671000 | 1224 | `target` | ✅ |
| 0.0.79 | 1.63.0-alpha-2026-08-05 | 1237 | `target` | ✅ |
| **0.0.82** | **1.64.0-alpha-1789764292000** | 1246 | `target` | ✅ **VDI + Mac** |
| 0.0.85 | 1.66.0-alpha-2026-11-09 | 1254 | `target` | ✅ ama Mac'te reset |

Executor (deterministik): Playwright Java `1.58.0` → `chromium-1208`.

## Komutlar

**Sürüm kontrolü** (Mac / VDI):
```bash
cd ~/Desktop/repo/uplift-test/playwright-mcp && grep '"version"' node_modules/@playwright/mcp/package.json node_modules/playwright-core/package.json
```
```powershell
Select-String '"version"' C:\tools\playwright-mcp\node_modules\@playwright\mcp\package.json, C:\tools\playwright-mcp\node_modules\playwright-core\package.json
```

**Önbellekteki tarayıcılar ve 1208'in yolu:**
```bash
ls ~/Library/Caches/ms-playwright/
find ~/Library/Caches/ms-playwright/chromium-1208 -path "*Contents/MacOS/*" -type f -perm -u+x | head -1
```

**Bir MCP sürümünün hangi Playwright'ı kullandığı:**
```bash
npm view @playwright/mcp@0.0.82 dependencies
```

**Ağı uygulamadan bağımsız ölçmek** (`302` = geçti, `000` = kesildi):
```bash
for i in 1 2 3 4 5 6 7 8 9 10; do curl -s -o /dev/null -w "%{http_code}\n" --max-time 10 https://ifctest.yapikredi.com.tr/ngc/indexNgc.do; sleep 1; done
```

**Belirli bir commit'i denemek ve geri dönmek** (`.mcp.json`'daki yerel değişiklik korunur):
```bash
git checkout 40a2a7f    # dene
git checkout master     # geri dön
```

**Yerel `.mcp.json`'ın pull'dan sağ çıktığını kontrol:**
```bash
git status --short .mcp.json; grep -n "executable-path\|--browser" .mcp.json
```

## Aynı gün yapılan kod değişiklikleri

| Commit | Ne | Durum |
|---|---|---|
| `40a2a7f` | Sayfayı düz listeye çeviren `LeanPage`, birkaç adımı tek turda yapan `do_steps` (`StepRunner`), `browser_fill_form` kaldırıldı, sayfa sınırı 6000 → 2500, ana menü sayfadan atılıyordu | **geri alındı** |
| `f713b14` | `40a2a7f`'in geri alınması — ağaç `6163fea` ile birebir aynı | master'da |
| `stash@{1}` (push edilmedi) | Girişte adresi geçici hatalarda 3 kez deneme; hata mesajının sebep tahmin etmemesi; `UPTEST_MCP_CONFIG` ile makineye özel `.mcp.local.json` | **bekliyor** (`stash@{0}` başka bir şey: geri alınan Token Takibi son-koşu/Geçmiş arayüzü) |
| `82008f1` | Dropdown araması: `browser_press_sequentially` ajana açıldı; hatalı `target`'a ipucu (`BudgetedTool.targetHint`); `RUN_SYSTEM`'e iki cümle; bu belge | master'da, birim testli; **VDI/Mac koşusuyla doğrulanmadı** |
| `0369853` | Token Takibi: bir turun tam girdisi ve çıktısı okunur (`TurnTranscript`, "tek metin" görünümü) — ajana giden hiçbir şey değişmedi, yalnız gösterim | master'da; `docs/TOKEN-TAKIBI.md` §3b |

**Dropdown araması (10:44 koşusu, MCP 0.0.82 ile — giriş artık çalışıyor):**
Firma Seçimi açıldı, ama sayfa 20.192 karakterdi ve sınır 6.000'i tutuyordu
(`browser_click sonucu 20192 -> 6153`). Select2'nin arama kutusu sayfanın
**sonunda** açıldığı için modelin gördüğü kısımda yoktu; ajan ref'ini
bulamayınca hedef uydurdu — `textbox "Seçiniz"` (CSS diye ayrıştırılıp
reddedildi), `textbox` (öyle eleman yok) — her birini beş kez denedi; 26 tur,
112.547 token, "Arama kutusuna 'san' yazılamadı". Düzeltme sayfaya dokunmuyor:
liste açılınca arama kutusu **zaten odakta**, ve MCP'nin hedefsiz yazan aracı
(`browser_press_sequentially` → `page.keyboard.type`, 0.0.82'de var) ajana
sunulmuyordu. Artık sunuluyor, yazdıktan sonra süzülmüş liste geri veriliyor,
ve hatalı bir `target` o anda ne yapılacağını söyleyen bir cevapla dönüyor.
Bilinen boşluk: Gherkin oluştur bu adımı henüz `enter "Firma Seçimi" "san"`'a
çevirmiyor (TapeRenderer `browser_press_sequentially`'yi tanımıyor).

`40a2a7f` neden geri alındı (09:53 koşusu, konsoldan): giriş ve menü koddan
yapıldı (`prelude=ON_SCREEN`), ama ajan senaryonun "ekranını aç" adımını yine
yaptı ve ana menüye tıkladı; sayfa 14.275 karaktere çıktı, 2500'lük sınır onu
kesti (`14275 -> 2653`). Aranan bağlantı kesilen kısımda kaldı; kesme notu
"snapshot'ı target/depth ile daralt" dediği için ajan daraltılmış snapshot
döngüsüne girdi; 23 turda bütçe aşıldı. Bu senaryoda sınır ayrıca ölümcüldü:
Firma Seçimi bir Select2 ve arama kutusu sayfanın **sonunda** açılıyor.

Aynı koşudan kalan ölçüm: 9 araç tanımı tur başına **2.673 karakter**
(~750 token) — tanımlar artık baş kalem değil.

## Bu sorunu çözerken yapılan yanlışlar — tekrarlanmasın

- **Kanıtsız sebep yazıldı, üç kez.** "Ağ Chromium'u reddediyor", "kod menüyü
  açamadı", "sebep Chromium 1254, kanıtlandı" — üçü de konsol satırı görülmeden
  söylendi, üçü de yanlıştı. İlki `CLAUDE.md`'ye, `application.properties`'e,
  bir hata mesajına ve bir teste yazılmıştı; commit edilmeden geri alındı.
  Kural: **sebep, onu gösteren konsol satırıyla birlikte yazılır.** Token Takibi
  tabloları özetler; kayıt konsoldur.
- **Çalışan yol, gerçekte ne çalıştırdığına bakılmadan karşılaştırıldı.**
  Deterministik koşunun Edge kullandığı varsayıldı; `UPTEST_BROWSER_CHANNEL=`
  (tanımlı ama boş) Playwright'ın kendi Chromium'u demek — `CLAUDE.md` bunu
  zaten yazıyordu.
- **Teşhis komutu yan etkili verildi.** `pkill -f` argümanlarda da eşleştiği
  için MCP sunucusunu öldürdü (kural 6).
- **Tek denemeye güvenildi.** 0.0.85+1208'in tek başarısı "kanıtlandı" diye
  okundu; aralıklı bir arızada bir değişikliğin işe yaradığını söylemek için
  aynı ayarla birkaç deneme gerekir.
