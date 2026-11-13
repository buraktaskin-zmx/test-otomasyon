# Veritabanı paneli — test verisini sorarak bulmak

Senaryum'un sol rayındaki **Veritabanı**, test Oracle'ına Türkçe soru sorup
senaryoya yazılacak **gerçek bir kayıt** bulmak içindir. "Ödenmemiş faturası
olan bir firma bul" sorusunun cevabı, o faturayı taşıyan kaydın anahtar
değerleridir — uydurulmuş bir CIF ile yazılan senaryo tarayıcıda çalışmaz.

Kurulum ve server tarafının tamamı: [`database-mcp-kurulum.md`](database-mcp-kurulum.md).

## Açmak

Varsayılan KAPALI. `DbController` ve `DbConfig` `@ConditionalOnProperty`
olduğu için kapalıyken uçlar 404 döner ve panel rayda hiç görünmez — tıpkı
`uptest.explore.enabled` gibi.

```properties
uptest.db.enabled=true
uptest.db.command=/Users/T0105189/Desktop/repo/database-mcp/run.sh
uptest.db.env.DB_HOST=ebnktst.sys.yapikredi.com.tr
uptest.db.env.DB_PORT=1818
uptest.db.env.DB_SERVICE=EBNKTST
uptest.db.env.DB_USER=NDVLIVE
```

**Şifre properties'e yazılmaz.** `DbSettings.environment()` orchestrator'ın
KENDİ ortamını alıp `uptest.db.env.*` değerlerini üstüne koyar, yani
`DB_PASSWORD` uygulamayı başlatan kabuktan (ya da IDE'nin run
configuration'ından) gelir ve hiçbir dosyaya yazılmaz.

## Neden `.mcp.json`'a eklenmedi

Eklenseydi iki şey birden bozulurdu ve ikisi de sessizdir:

1. `spring.ai.mcp.client.stdio.servers-configuration` o dosyadaki **her**
   server'dan TEK bir `ToolCallbackProvider` üretir, ve
   `ExploreController.toolsFor` bir izin listesi yokken (Ekran Keşfi) onun
   tamamını tarayıcı ajanına verir. Tarayıcı ajanı `execute_query` görürdü.
2. Python süreci açılmazsa — VPN kapalı, yol yanlış, Oracle reddetti; üçü de
   bu kurulumda yaşandı — o provider'ın başlatılması tarayıcı ajanını da
   yanına alırdı.

Bu yüzden `DbToolbox` kendi `McpSyncClient`'ını **bean olarak yayınlamaz**.
Yayınlasaydı Spring AI onu yine o tek provider'a katardı; ikinci bir
`ToolCallbackProvider` bean'i ise `ExploreController`'ın injection'ını
belirsiz hale getirirdi. Kapsülleme burada bir özelliktir, üslup değil.

## Bağlantı tembeldir

`DbToolbox` ilk SORUDA bağlanır, uygulama açılırken değil. Gerekçesi ölçülmüş
bir durum: VPN kapalıyken `socket.gaierror` alınır ve bu hata başlangıçta
uygulamayı tamamen açılmaz hale getirirdi. Şimdi yalnız panel "bağlı değil"
der, sebebini yazar, ve **Yeniden bağlan** düğmesi süreci yeniden başlatır —
ilk denemenin hatası hatırlandığı için bu düğme olmadan düzeltme fark
edilmezdi.

## Sohbet geçmişi

Soru + önceki konuşma birlikte gönderilir (sunucuda son 8 tur ile sınırlı),
çünkü veri aramak tek soruyla bitmez: "peki o firmanın cari hesabı ne"
sorusu ancak öncekiyle anlamlıdır.

**Saklanmaz.** Sayfa yenilenince silinir; bu bilinçli: cevaplar bankanın test
veritabanından gerçek müşteri anahtarları taşır ve zaten Token Takibi'nde
kayıtlıdır (oturum türü `db`).

## Güvenlik

Üç katman, ve en sağlamı en alttadır:

1. **DB kullanıcısı.** En güçlü koruma read-only bir Oracle kullanıcısıdır.
   Bugün `NDVLIVE` öyle değil.
2. **Python tarafı.** `tools/query.py` yalnız `SELECT`/`WITH` kabul eder,
   gömülü yazma ifadelerini reddeder, satırı `fetchmany` ile sınırlar ve
   `FORBIDDEN_OUTPUT_COLUMNS` (tckn, iban, email, telefon…) kolonlarını
   `***` yapar. Kontrol tırnaklı değerleri boşaltılmış kopyada yapılır,
   yoksa `WHERE durum = 'SET'` masum sorgusu reddedilirdi.
3. **Prompt.** En zayıfı, ve bu dosyanın tekrar tekrar öğrendiği ders:
   kural koda yazılmadıkça kural değildir. Prompt yalnız yönlendirir.

Modelin kurum içi vLLM olması bu panelin IntelliJ + Copilot yolundan asıl
farkıdır: orada DB'den dönen satırlar banka dışı bir LLM'e gider.

## Dosyalar

| yer | ne |
|---|---|
| `orchestrator/db/DbSettings.java` | komut, env, timeout; env birleştirme |
| `orchestrator/db/DbToolbox.java` | MCP client, tembel bağlanma, hata metni |
| `orchestrator/db/DbConfig.java` | tek bean: DbToolbox |
| `orchestrator/db/DbController.java` | `/api/v1/db/tools`, `/ask`, `/reconnect` |
| `orchestrator/db/DbPrompts.java` | sistem promptu |
| `frontend/src/db/DbPanel.tsx` | panel |
| `frontend/src/hooks/useDbTools.ts` | durum + yeniden bağlan |
