# AI ile çalıştır — Ayarlar ve sabitler

[← İçindekiler](README.md) · [← Sonuç, kayıt ve Gherkin](07-sonuc-kayit-gherkin.md) · [Tarihçe ve dersler →](09-tarihce-ve-dersler.md)

---

## 22. Ayarlar ve sabitler

### 22.1 `application.properties`

| Anahtar | Değer | Ne |
|---|---|---|
| `uptest.explore.enabled` | `true` | Uç noktalar yalnız bununla var |
| `spring.ai.mcp.client.stdio.servers-configuration` | `file:.mcp.json` | MCP sunucusunun tanımı |
| `spring.ai.mcp.client.request-timeout` | `120s` | Bir MCP çağrısının üst sınırı |
| `uptest.explore.settle-ms` | `1500` | Eylemden sonra sayfanın oturması; prelude ve overlay bekleme adımı |
| `uptest.explore.ai-run.token-budget` | `100000` | %80'de not, %100'de duvar |
| `uptest.explore.ai-run.tool-budget` | `40` | Araç çağrısı tavanı (sonsuz döngüye karşı) |
| `uptest.explore.ai-run.describe-cap` | `220` | Araç açıklaması karakter sınırı |
| `uptest.explore.ai-run.page-cap` | `12000` | Özetlenmiş sayfanın karakter sınırı (6.000'di; "Fatura İptal Et" 6.885. karakterde kalıyordu) |
| `uptest.explore.tape-dir` | `./runs/mcp` (varsayılan) | Bant klasörü |
| `uptest.trace.dir` | `./runs/llm` | Trace metinleri |

### 22.2 `.mcp.json` (repo kökü, Windows / VDI)

```json
{"mcpServers": {"playwright": {
  "command": "node",
  "args": ["C:/tools/playwright-mcp/node_modules/@playwright/mcp/cli.js",
           "--browser", "msedge", "--isolated",
           "--allowed-origins", "https://ifctest.yapikredi.com.tr;https://intest.yapikredi.com.tr",
           "--output-dir", "./runs/mcp"]}}}
```

`@playwright/mcp` **0.0.82'ye sabittir** (`npm install --save-exact`).
0.0.85 Mac'te ilk adreste `ERR_CONNECTION_RESET` verdi. Alt sınır 0.0.75'tir:
`AiRunPrelude` ve `DateTool` CSS seçiciyi `target` adıyla gönderir. Mac'in
`.mcp.json`'u yereldir, commit edilmez.

### 22.3 Kod sabitleri

| Sabit | Değer | Sınıf | Ne |
|---|---|---|---|
| `NAVIGATE_ATTEMPTS` | 5 | AiRunPrelude | İlk adres, geçici ağ hatasında |
| `FIELD_ATTEMPTS` | 6 | AiRunPrelude | İlk giriş kutusu |
| `MENU_ATTEMPTS` | 3 | AiRunPrelude | Ana menü → ekran |
| `LOAD_POLLS` | 20 | AiRunPrelude | Ekran yüklenene kadar bakış (~30 sn) |
| `STUCK_AFTER` | 4 | AiRunPolicy | Üst üste olmayan eylem |
| `ATTEMPTS` | 6 | BusyPage | Overlay'e takılan çağrı (~30 sn) |
| `POLLS` | 20 | BusyPage | Sayfa okunmadan önce overlay bekleme |
| `MAX_STEPS` | 8 | StepRunner | do_steps başına adım |
| `OPTIONS_SHOWN` / `OPTIONS_HEAD` | 15 / 3 | LeanResults | Liste kısaltma eşiği / baştan tutulan |
| `KEEP_UNDER` | 300 | CompactingAdvisor | Bundan kısa eski sonuç kısaltılmaz |
| `LARGEST_RESULT` | 12.000 | BudgetedTool | İlk mesajdaki sayfa sınırı; Ekran Keşfi'nin sınırı |
