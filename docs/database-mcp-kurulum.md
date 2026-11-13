# Database MCP server — Mac kurulumu

Oracle test veritabanına salt okunur erişen bir Python MCP server'ı. Kaynağı
Confluence'taki "Database MCP Server Örnek" sayfası (Windows için yazılmış);
burada Mac'e uyarlanmış hâli ve **neden** öyle yapıldığı var.

Kurulum yeri: `/Users/T0105189/Desktop/repo/database-mcp` — git repolarının
DIŞINDA. DB bilgileri hiçbir repoya girmesin diye.

## Sürümler sabittir

```
oracledb==26.1.0
mcp==1.30.0
```

`mcp` 2.x'e **çıkılamaz**: Confluence'taki kod düşük seviye `Server` API'sini
`@app.list_tools()` dekoratörleriyle kullanıyor, 2.3.0'da o dekoratörler yok
(`AttributeError: 'Server' object has no attribute 'list_tables'`) ve yerine
`add_request_handler` gelmiş. `uv pip install mcp` en yeniyi kurar, yani
`requirements.txt` olmadan kurulum sessizce kayar — Playwright MCP'yi 0.0.82'ye
sabitlemek zorunda kaldığımız durumun aynısı.

## Kurulum

Homebrew **kurulamadı** (sudo yetkisi yok), python.org `.pkg` de yönetici
ister. Çalışan yol `uv`: ev dizinine kurulur, Python 3.12'yi kendi indirir,
sisteme dokunmaz. macOS'un kendi Python'u 3.9.6 ve `mcp` 3.10+ istiyor.

```sh
curl -LsSf https://astral.sh/uv/install.sh | sh
source "$HOME/.local/bin/env"

cd ~/Desktop/repo/database-mcp
rm -rf __pycache__ tools/__pycache__      # Windows'tan gelen 3.12 pyc'leri
uv venv --python 3.12
uv pip install -r requirements.txt
```

`run.bat`'ın Mac karşılığı `run.sh`:

```sh
#!/bin/sh
cd "$(dirname "$0")" || exit 1
exec ./.venv/bin/python server.py
```

`cd` şart: `server.py` `from tools.schema import ...` diyor ve göreli import
çalışma dizinine bağlı.

## Tool'lar

| tool | ne yapar |
|---|---|
| `list_tables` | şemadaki tablolar (`user_tables`) |
| `describe_table` | kolonlar, PK, FK |
| `get_relationships` | FK ilişkileri |
| `list_indexes` | index'ler |
| `execute_query` | **salt okunur SELECT**, satırları döner |
| `explain_query` | execution plan |

`execute_query` zip'te YOKTU, sonradan eklendi — veri okuyan tek tool odur ve
hedef veri bulmaktı. Korumaları `tools/query.py`'de:

1. `SELECT`/`WITH` ile başlamayan reddedilir.
2. Gömülü yazma ifadesi (`DROP`, `DELETE`…) reddedilir. Arama, **tırnaklı
   değerleri boşaltılmış** bir kopyada yapılır; yoksa `WHERE durum = 'SET'`
   masum sorgusu `SET` yüzünden reddedilirdi.
3. Satır sınırı `fetchmany` ile uygulanır (varsayılan 50, tavan 500). SQL'e
   `FETCH FIRST` eklemek alt sorgulu / UNION'lu metni bozar.
4. `FORBIDDEN_OUTPUT_COLUMNS` kolonları `***` olur. Satır gizlenmez: "kaç
   kayıt var" bilgisi veri ararken gereken şeydir.
5. Hiç `commit` edilmez.

Aynı `_read_only` kontrolü `explain_query`'ye de kondu; orada hiç yoktu.

> `explain_query` tam salt okunur DEĞİL: `plan_table`'a DELETE atıp
> `commit()` eder. Şemada `PLAN_TABLE` ve yazma yetkisi ister. Tam read-only
> bir DB kullanıcısına geçilirse bu tool çalışmaz.

## Bağlantı

```
ebnktst.sys.yapikredi.com.tr:1818/EBNKTST  kullanıcı NDVLIVE  (Oracle 19)
```

`oracledb` **thin mode** yetiyor, Instant Client gerekmiyor — DataGrip de aynı
bağlantıyı Thin sürücüyle açıyor.

**VPN açık olmalı.** Kapalıyken hata `socket.gaierror: nodename nor servname
provided` olur: Python adı IP'ye çeviremiyor, yani DB'ye bağlanmayı denemiyor
bile. Bu, `ifctest`'teki bağlantı sıfırlanmalarından farklıdır — orada ad
çözülür, bağlantı kesilir.

`list_tables` 1306 tablo döndürüyor, yani tablolar NDVLIVE'ın kendi
şemasında; `user_*` görünümleri yeterli, `all_tables`'a geçmek gerekmedi.

## Doğrulama (DB'ye bağlanmadan ve bağlanarak)

```sh
# server.py yükleniyor mu (göreli import'lar dahil)
./.venv/bin/python -c "import server; print('ok')"

# MCP protokolü: IntelliJ'in / orchestrator'ın yapacağının aynısı
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}' '{"jsonrpc":"2.0","method":"notifications/initialized"}' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | ./run.sh
```

İkincisi altı tool adını döndürmeli.

## IntelliJ (Copilot) — şimdilik yok

Confluence'taki yol IntelliJ + GitHub Copilot MCP'si. 13.11.2026'da kurulmadı:
Copilot plugin'i yüklü değil ve lisans alınmadı. Alınırsa `mcp.json`'a:

```json
"oracle-db": {
  "type": "stdio",
  "command": "/Users/T0105189/Desktop/repo/database-mcp/run.sh",
  "env": { "DB_HOST": "...", "DB_PORT": "1818", "DB_SERVICE": "EBNKTST",
           "DB_USER": "NDVLIVE", "DB_PASSWORD": "..." },
  "tools": ["*"]
}
```

İki uyarı: Confluence sayfası bankada Copilot üzerinden custom MCP'nin merkezi
olarak **kapatıldığını** söylüyor; ve Copilot banka DIŞI bir LLM, yani DB'den
dönen satırlar oraya gider. Senaryum'un kendi paneli kurum içi vLLM kullanır
— bkz. [`veritabani-paneli.md`](veritabani-paneli.md).
