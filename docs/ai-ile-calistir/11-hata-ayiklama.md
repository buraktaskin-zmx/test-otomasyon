# AI ile çalıştır — Hata ayıklama

[← İçindekiler](README.md) · [← Senaryo yazım rehberi](10-senaryo-yazim-rehberi.md) · [Bilinen açıklar ve dosya haritası →](12-bilinen-aciklar.md)

---

## 25. Hata ayıklama: belirti → log satırı → sebep

Kural: sebep ancak onu gösteren log satırıyla söylenir. 12.11.2026'da kanıtsız
söylenen üç sebep yanlış çıktı.

### 25.1 Log satırları (sunucu konsolu, sırayla)

| Satır | Anlamı |
|---|---|
| `[uptest] prelude: adres acilamadi (2/5) — ERR_CONNECTION_RESET, tekrar deneniyor` | Ağ kesti, tekrar deneniyor |
| `[uptest] prelude: adres 3. denemede acildi — baglanti kararsiz` | Yol kararsız ama geçti |
| `[uptest] prelude: giris kutusu (#userCode) 2. denemede yok, bekleniyor` | Giriş sayfası geç geldi |
| `[uptest] prelude: prelude_login tamam (4100 ms)` / `olmadi — …` | Giriş sonucu |
| `[uptest] prelude: menu 1. denemede acilmadi — …` | Menü tıklaması tutmadı |
| `[uptest] prelude: ekran 3. bakista yuklendi` | Yükleme katmanı 3 bakışta kalktı |
| `[uptest] prelude: "X" 30 sn sonra hala yukleniyor — ajan baslatilmiyor` | Ekran gelmedi, token harcanmadı |
| `[uptest] ai-run: cerceve 4 alt agac (ust bilgi/menu/alt bilgi) gosterilmeyecek` | PageChrome öğrendi (0 = öğrenemedi, sayfa tam gider) |
| `[uptest] ai-run: prelude=ON_SCREEN, 8 tarayici araci, talimat 3120 karakter, token butcesi 100000` | Ajan başlıyor |
| `[uptest] compact: 9 mesaj, 3 eski sonuc kisaltildi (…), options=OpenAiChatOptions` | Her tur. `options` başka bir sınıf ise ClassCastException riski var |
| `[uptest] explore -> do_steps {"steps":[…]}` | Modelin çağrısı, argümanlarıyla |
| `[uptest] explore: browser_click sonucu 10413 -> 2210 karakter` | Özetleme ne kadar kesti |
| `[uptest] explore: browser_click — sayfa yukleniyor (overlay), 2. deneme, tekrar bekleniyor` | BusyPage.waitOut |
| `[uptest] explore: sayfa 6200 ms yuklendi (overlay kalkti)` | waitWhileBusy |
| `[uptest] explore: senaryoda gecmeyen onay 'evet' — reddedildi` | Onay engeli çalıştı |
| `[uptest] explore: ayni cagri ayni sayfada ikinci kez — browser_type reddedildi` | Tekrar engeli |
| `[uptest] ai-run kod: 15/17 adım kodla okundu; 16. adım okunamadı: …` | Kodun okuyabildiği adımlar; okunamayan adımdan sonrasını ajan yapar ([§24.6](10-senaryo-yazim-rehberi.md)) |
| `[uptest] ai-run kod 7. type "Orijinal Fatura No" = "2222" → YAPILMADI: …` | Kod bu adımı yapamadı (çoğunlukla ad ekrandakinden farklı) |
| `[uptest] ai-run kod: ajan 7. adımdan devralıyor` / `bütün adımlar kodla yapıldı — yalnız karar turu` | Hangi yoldan devam edildiği |
| `[uptest] explore: 4 islem ust uste olmadi — kosu kodla bitiriliyor` | Takılma; sonuç kodla bildirilir |
| `[uptest] explore: token butcesi doldu (100233/100000) — kosu kodla bitiriliyor` | Bütçe duvarı (yalnız `token-budget` > 0 ise; varsayılan kapalı) |
| `[uptest] ai-run: kosu kodla durduruldu — <neden>` | Yukarıdaki ikisinin sonucu: AI ÖZET'te "Koşu kodla durduruldu" |
| `[uptest] explore: sayfayi yenileme (…) — reddedildi` | F5 engeli |
| `[uptest] set_date: choose the month -> {…}` | Tarih aracının iç adımları |
| `[uptest] explore: sonuc bildirildi — ajanin iddiasi GECTI (4 dogru, 0 gecemedi, 0 uyari, 2/3 beklenen cevaplandi; karar kodla kesinlesir)` | Ajanın iddiası; cevapsız Beklenen kodla Geçmedi olur |
| `[uptest] explore: B2 son ekranda kontrol edilemez — [Kurum Adı, …] son ekranda yok, onceki bir ekrandaydi` | Son Beklenen yanlış yerde; o madde Geçmedi. Adımın altına `Beklenen:` olarak taşıyın |
| `[uptest] explore: ajan 'Geri' adimini yapildi dedi ama bantta bu tiklama yok — sonuc GECMEDI'ye cevrildi` | ClaimCheck düzeltti |
| `[uptest] ai-run: bitti — 34120 token, sonuc bildirildi=true` | Toplam |
| `[uptest] explore: tarayici yok ("has been closed") — kosu sonlandiriliyor` | Pencere kapandı |
| `[uptest] explore(explore): kosu basarisiz — <Sınıf: mesaj>` + yığın izi | 502'nin kök sebebi |

### 25.2 Belirti tablosu

| Belirti | İlk bakılacak | Olası sebep | Bölüm |
|---|---|---|---|
| "Ajan başlatılmadı: … AĞ / BAĞLANTI" | `prelude: adres acilamadi` satırları; aynı dakikada `curl -I <url>` | VPN / ağ (Mac) | [§6.3](02-istek-ve-prelude.md) |
| "Ajan başlatılmadı: … yüklenmedi" | `prelude: … hala yukleniyor` | Uygulama yavaş; deterministik koşuyla karşılaştır | [§6](02-istek-ve-prelude.md) |
| Giriş Mac'te sürekli `ERR_CONNECTION_RESET` | `C:/tools/playwright-mcp` (Mac'te kendi yeri) sürümü | MCP 0.0.85 → 0.0.82'ye dön | [§22.2](08-ayarlar.md) |
| Ajan menüye tekrar tıklıyor | Tur #1 kullanıcı mesajı | Senaryoda "ekranı aç" adımı | [§7](02-istek-ve-prelude.md), [§24](10-senaryo-yazim-rehberi.md) |
| Ajan bir öğeyi "göremiyor" | `sonucu A -> B karakter`; bantta tam sayfa ile modelin gördüğünü karşılaştır | 12.000'de kesilmiş; uzun listede gizlenmiş; çerçevede kalmış | [§13](05-sayfa-ve-baglam.md) |
| Seçenek seçilemiyor (Select2) | `explore -> browser_type …select2…` | Göstergeye yazma; ad tırnaksız ya da yanlış | [§10.5](04-araclar-ve-kapi.md), [§24](10-senaryo-yazim-rehberi.md) |
| `do_steps` hep "YAPILMADI: … yok" | do_steps cevabındaki satır | Ad sayfadaki yazımdan farklı (tam eşleşme ister) | [§15.2](06-do-steps-ve-set-date.md) |
| `do_steps` "… 3 … ile eşleşti" | Aynı | Ad birden çok öğede var; ajan tek adımla yapmalı | [§15.3](06-do-steps-ve-set-date.md) |
| 5 sn timeout + `intercepts pointer events` | `sayfa yukleniyor (overlay)` satırları | Yükleme katmanı >30 sn | [§12](04-araclar-ve-kapi.md) |
| "Geçti" ama ekran yanlış | Bant; `ClaimCheck` satırı yoksa adım tırnaksız olabilir | ClaimCheck yalnız tırnaklı "tıkla/bas" adımlarına bakar | [§17.2](07-sonuc-kayit-gherkin.md) |
| Sonuç: "Sonuç bildirilmedi" | Son turlar; `EXHAUSTED` var mı | 40 çağrı bitti ya da model düz yazıyla bitirdi | [§10.2](04-araclar-ve-kapi.md) |
| Token > 100k | Token Takibi tur listesi: hangi araç tekrarlanıyor | Takılma, overlay, görünmeyen öğe | [§11](04-araclar-ve-kapi.md), [§21](07-sonuc-kayit-gherkin.md) |
| 502 `ClassCastException … OpenAiChatOptions` | `compact: … options=` | İkinci ChatClient kurulmuş | [§5.1](02-istek-ve-prelude.md) |

---

## 25.3 Hangi sırayla bakılır

```
koşu bozuldu
   │
   ├─ 1. SENARYO: tırnaklar, öğe türleri, "ekranı aç", onay sözcükleri ── 10-senaryo-yazim-rehberi.md
   │      (12.11.2026: aynı kodla bir senaryo geçti, kötü yazılmış ikizi geçmedi)
   │
   ├─ 2. AJAN HİÇ BAŞLADI MI?   cevap "Ajan başlatılmadı: …" mı?
   │      evet → AĞ / BAĞLANTI / SAYFA / yüklenmedi → 25.4'teki curl, VPN, deterministik koşu
   │
   ├─ 3. BANT: ajan ne yaptı?    25.4'teki özet komutu → REFUSED / ERROR satırları
   │      REFUSED → hangi bekçi, neden (04-araclar-ve-kapi.md §10.3)
   │      ERROR   → MCP'nin hata metni (overlay mı, yanlış target mı, element yok mu)
   │
   ├─ 4. MODEL NE GÖRDÜ?         Token Takibi → oturum → ilgili tur → "tam girdi"
   │      aradığı öğe özette var mı? yoksa 12.000'de mi kesildi, listede mi gizlendi?
   │
   └─ 5. SÜRÜM / ORTAM           MCP 0.0.82 mi, orchestrator yeniden başlatıldı mı, UI yeniden derlendi mi
```

Bir sebebi ancak onu gösteren log ya da bant satırını alıntılayarak söyle.

---

## 25.4 Kopyala-yapıştır komutlar

Bantlar orchestrator'ın **çalışma dizinine** göre `runs/mcp/` altına düşer:
komut satırından başlatıldıysa repo kökü, IntelliJ'den başlatıldıysa çoğu
zaman `uptest-orchestrator/`. İkisine de bakılır.

**En son bant (Mac / Git Bash):**

```bash
ls -t runs/mcp/*/tape.json uptest-orchestrator/runs/mcp/*/tape.json 2>/dev/null | head -1
```

**En son bant (Windows PowerShell):**

```powershell
Get-ChildItem runs\mcp, uptest-orchestrator\runs\mcp -Recurse -Filter tape.json -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
```

**Bandın özeti: her kayıt tek satır** (Windows'ta `python3` yerine `python`):

```bash
python3 -c 'import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
print(d["tapeId"], d.get("entryCount"), "kayıt")
for e in d["entries"]:
    print("%3d %6.1fs %-26s %-8s %6d  %s" % (e["seq"], e.get("atMs", 0) / 1000, e["tool"], e["outcome"],
          len(e.get("result") or ""), (e.get("args") or "")[:90]))' "$(ls -t runs/mcp/*/tape.json | head -1)"
```

Çıktı şöyle okunur: `prelude_*` kod, sonuç uzunluğu ~10.000 olan
`browser_snapshot`'lar kodun eylemden sonra aldığı sayfalar, geri kalanı
ajan. Örnek: [13-ornek-kosu-2628c4fd.md](13-ornek-kosu-2628c4fd.md) §2.

**Yalnız reddedilen ve hata alan çağrılar, ilk satırlarıyla:**

```bash
python3 -c 'import json,sys
for e in json.load(open(sys.argv[1],encoding="utf-8"))["entries"]:
    if e["outcome"]!="OK": print(e["seq"], e["tool"], e["outcome"], (e.get("result") or "").strip().splitlines()[0][:160])' <tape.json yolu>
```

**Ajanın raporu (report_result argümanları):**

```bash
python3 -c 'import json,sys
for e in json.load(open(sys.argv[1],encoding="utf-8"))["entries"]:
    if e["tool"]=="report_result": print(json.dumps(json.loads(e["args"]),ensure_ascii=False,indent=2))' <tape.json yolu>
```

**Bir ref'in ne olduğu** (ör. ajan `f3e703`'e tıkladı, o neydi):

```bash
grep -oE -- '- [a-z]+[^[]{0,80}\[ref=f3e703\]' <tape.json yolu> | head -1
```

Çıktı `- cell \\\"103212\\\" [ref=f3e703]` gibidir. Ters bölüler JSON
kaçışıdır, öğe `cell "103212"`'dir.

**Ağ mı tarayıcı mı (Mac, VPN açık).** Koşunun bozulduğu **aynı dakikada**
çalıştır. curl da kesiliyorsa sorun yoldadır, tarayıcıda değil:

```bash
for i in 1 2 3 4 5; do curl -sS -o /dev/null -w "%{http_code}\n" https://ifctest.yapikredi.com.tr/ngc/indexNgc.do; done
```

`302` normaldir. `curl: (35)` / `(56) … reset by peer` kesinti demektir.
Ayrıntı: `docs/MCP-TARAYICI-SORUNU.md`.

**MCP sürümü** (beklenen `@playwright/mcp@0.0.82`):

```bash
cd C:/tools/playwright-mcp && npm ls @playwright/mcp      # VDI; Mac'te kendi kurulum klasörü
```

**Ajana sunulan araçlar, şemalarıyla** (MCP bağlı mı, araçlar gelmiş mi):

```bash
curl -s http://localhost:8035/api/v1/explore/tools | python3 -m json.tool | grep '"name"'
```

Liste boşsa modele hiç tarayıcı verilmemiştir. Bundan sonraki her hatayı
prompta ya da senaryoya bağlamak yanlış olur.

**Ajana ilk ne gitti:** Token Takibi → koşunun oturumu → tur #1 → "Bu turun
tam girdisini göster" → `kullanıcı` mesajı. Burada "… ekranına git" satırı
görünüyorsa ekran koddan açılamamıştır ya da `withoutGoTo` çalışmamıştır.

---

## 25.5 Değişiklikten sonra ne yeniden başlatılır

| Değişen | Gereken |
|---|---|
| `orchestrator/explore/*.java`, `application.properties` | Orchestrator'ı yeniden derle ve başlat |
| `frontend/src/**` (ör. `ProductPanel.tsx`, `scenarioProse.ts`) | `npm run build` + orchestrator yeniden başlat (UI jar'dan sunulur; build git'te değil) |
| `.mcp.json` ya da MCP kurulumu | Orchestrator yeniden başlat (MCP sunucusunu o başlatır) |
| Senaryo metni (Kayıtlı Senaryolar'da) | Hiçbir şey; bir sonraki koşu yeni metni kullanır |
