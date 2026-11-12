# AI ile çalıştır — Sayfa ve bağlam

[← İçindekiler](README.md) · [← Araçlar ve kapı](04-araclar-ve-kapi.md) · [do_steps ve set_date →](06-do-steps-ve-set-date.md)

---

## 13. Sayfanın modele yolculuğu

MCP'nin döndürdüğü ham sonuç ile modelin gördüğü metin farklıdır. **Bant
her zaman ham sonucu tutar.**

```
MCP ham sonucu  [{"text":"### Ran Playwright code\n```js\nawait page...\n```\n### Page\n- Page URL: ...\n### Snapshot\n```yaml\n- generic [ref=f3e1]:\n ..."}]
     │
     │ 1. LeanResults.unwrap        JSON dizisini aç, "\n" → gerçek satır sonu
     │                              (bu adım yokken sınıf GERÇEK sayfada hiçbir şey silmiyordu:
     │                               10.911 → 10.911; birim testleri düz metinle beslenmişti)
     ▼
düz metin
     │ 2. PageChrome.strip          ekran açıldığında öğrenilen çerçeve alt ağaçlarını at,
     │                              yerine tek satır: "- (üst bilgi, ana menü ve alt bilgi gösterilmedi …)"
     ▼
     │ 3. LeanResults.summarise(metin, 12000, talimat)
     │    a. RAN_CODE       "### Ran Playwright code ```js … ```" yankısı silinir
     │    b. dropScaffold   ADI OLMAYAN iskelet düğümler silinir:
     │                      generic, group, list, listitem, paragraph, region, main, banner,
     │                      navigation, rowgroup, table, section, … (çocukları kalır)
     │                      AMA "generic [ref=…]: Firma Seçimi" gibi kendi METNİ olan kalır (etiket)
     │    c. tidy           gürültü satırları: "- /url:", "Page Title", "Console:", "### Events",
     │                      "New console entries", "- [Snapshot](dosya)"
     │                      " [cursor=pointer]" atılır
     │                      ": activate to sort column ascending" atılır
     │                      ebeveyninin adını tekrarlayan metin çocuğu atılır
     │                      >15 seçenekli liste kısaltılır (aşağıda)
     │                      boş kalan başlıklar ve sondaki ":" temizlenir
     │    d. cap            12.000 karakterden sonrası "[... kesildi …]" notuyla kesilir
     ▼
modelin gördüğü sayfa
```

### 13.1 Uzun seçenek listesi

Firma Seçimi açıkken sayfanın yarısı (13.714 karakterin 6.860'ı) tek bir
listenin 113 firmasıydı ve senaryo bunlardan birini adlandırıyordu. 15'ten
uzun bir seçenek dizisinde şunlar **tutulur**:

- ilk 3 seçenek (`OPTIONS_HEAD`)
- `[selected]` olan
- adı **talimatta geçen** her seçenek (katlanmış alt dize araması)

Gerisi tek satırla özetlenir:

```
- (99 seçenek daha gösterilmedi. Aradığın burada yoksa liste açıkken adını yaz: browser_press_sequentially, hedefsiz)
```

Bunun **senaryo yazımına etkisi** var: firma adı senaryoda **ekranda
yazdığı gibi** geçmezse listede görünmez. Ajan da ya arama kutusuna yazmak
zorunda kalır ya da şaşırır ([§24](10-senaryo-yazim-rehberi.md), bant a73ead34).

### 13.2 PageChrome: bankanın çerçevesi

Firma Fatura İşlemleri'nin 10.413 karakterinin yaklaşık 3.400'ü her sayfada
aynı olan çerçeveydi: Çıkış, Ayarlarım, kullanıcı adı, HESAPLAR … SİGORTA
menüsü, son giriş saati, telif satırı.

```
learn(ilk sayfa, ANA MENÜ, Ekran, senaryo)
  1. heading "<Ekran>"          → başlık düğümü
  2. link "<ANA MENÜ>"          → menü düğümü
  3. başlıktan köke giden yol; ANA MENÜ'yü İÇERMEYEN en yüksek ata = EKRAN
  4. yol üzerindeki her atanın, yolda olmayan çocukları = ÇERÇEVE  (ref'leriyle hatırlanır)
  5. senaryonun adlandırdığı bir şeyi içeren çerçeve alt ağacı TUTULUR

     kök
     ├── banner  (Çıkış, Ayarlarım, kullanıcı)        ← çerçeve
     ├── navigation (HESAPLAR … NAKİT YÖNETİMİ …)     ← çerçeve
     ├── main
     │    └── EKRAN ── heading "Firma Fatura İşlemleri", form, ızgara, İşlemler paneli
     └── contentinfo (telif, son giriş)                ← çerçeve
```

40a2a7f'nin hatasını (sayfayı akıllıca küçültüp ajanın bir sonraki eylemde
ihtiyaç duyduğu bağlantıyı gizlemek) tekrarlamamak için şu sınırlar var:

- yalnız **kod ekranı açtıysa** (`ON_SCREEN`) öğrenilir;
- yalnız ekran açıldığında **var olan** ref'ler atılır. Sonradan gelen bir
  diyalog, Select2 listesi ya da arama kutusu tam gösterilir;
- sayfa sıfırdan yenilenirse ref'ler değişir, hiçbir şey eşleşmez ve sayfa
  tam gelir;
- atılan yerde tek satırlık not kalır.

---

## 14. CompactingAdvisor: konuşma tur tur

**Sorun (ölçüldü, 11.11.2026):** 52 tur, 1,48 milyon girdi token'ı. Tool
sonuçlarının payı %66'ydı. Sonuçlar büyük değildi (snapshot ~2.200, tık ~160
token), ama araç döngüsü **her turda bütün konuşmayı yeniden gönderir**. 3.
turun sayfası 49 kez daha ödendi.

```
Spring AI zinciri (her tur):
  ToolCallingAdvisor (MIN+300)  ──►  CompactingAdvisor (ORDER 0)  ──►  ChatModel (MAX)
       döngüyü kurar                  HER TURDA, gönderimden hemen önce       vLLM'e gider

Tur N'de modele giden mesajlar:
  [system]    RUN_SYSTEM + RESULT_RULES                                  (her tur, tam)
  [tools]     11 araç tanımı (LeanTools)                                 (her tur, tam)
  [user]      ilk mesaj; <<EKRAN>> bloğu → "[giriş sonrası ilk ekran kısaltıldı …]"
  [assistant] tool_call #1
  [tool]      #1 sonucu → "await page.getByRole(...).click();\n[eski ekran kısaltıldı]"
  [assistant] tool_call #2
  [tool]      #2 sonucu → "<ilk satır>\n[eski sonuç kısaltıldı: 8123 karakter]"
  ...
  [assistant] tool_call #N-1
  [tool]      #N-1 sonucu → TAM  (ajanın üzerinde çalıştığı güncel sayfa)
```

- 300 karakterden kısa sonuçlara dokunulmaz (`KEEP_UNDER`).
- Kesilen bir sonuç, varsa çalıştırılan Playwright satırına iner
  (`TapeEvidence.code`). Böylece **ne yapıldığı** konuşmada kalır, **hangi
  sayfada yapıldığı** gider. Eski bir sayfanın ref'leri zaten ölüdür.
- Kısaltma yalnız **gönderilene** uygulanır. Bant ve trace tam metni tutar.
- Her tur bir log satırı yazar:
  `[uptest] compact: 14 mesaj, 5 eski sonuc kisaltildi (41230 -> 512 karakter), options=OpenAiChatOptions`
