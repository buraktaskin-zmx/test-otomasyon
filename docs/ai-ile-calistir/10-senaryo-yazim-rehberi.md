# AI ile çalıştır — Senaryo yazım rehberi

[← İçindekiler](README.md) · [← Tarihçe ve dersler](09-tarihce-ve-dersler.md) · [Hata ayıklama →](11-hata-ayiklama.md)

---

## 24. Senaryo nasıl yazılmalı

Aynı kod, aynı ekran ve aynı firmayla bir senaryo geçti, öteki geçmedi
(12.11.2026). Fark yalnızca yazımdaydı. Sebebi mimaride: **kod senaryoyu
yalnız tırnaklı adlar üzerinden tanır.** Bunlar `do_steps`'in tam eşleşmesi
([§15](06-do-steps-ve-set-date.md)), `ClaimCheck` ([§17](07-sonuc-kayit-gherkin.md)), liste kısaltmada tutulan seçenekler ([§13.1](05-sayfa-ve-baglam.md)),
çerçevede tutulan alt ağaçlar ([§13.2](05-sayfa-ve-baglam.md)) ve onay engeli ([§10.4](04-araclar-ve-kapi.md)). Tırnaksız ya
da ekli bir ad bu mekanizmaların hepsini by-pass eder ve modeli tahmine
iter.

**GEÇTİ** ("İptal ekranından Geri ile listeye dönülür"):

```
1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
2. Izgarada Ödeme Durumu "Ödenecek" olan ilk satırı seç ve başka satır seçme
3. İşlemler panelindeki "İptal Et" bağlantısına tıkla
4. Açılan iptal ekranında yalnızca "Hayır" düğmesine tıkla
Beklenen: "Firma Fatura İşlemleri" listesi yeniden görünür
```

**GEÇMEDİ**, ASAŞ seçilemedi (bant a73ead34, "Güncelleme ekranından Geri…").
Ajan listeyi açtı, ASAŞ'ı gördü, listeye yeniden tıklayıp kapattı, sonra
adı tuşlarla düzenlenemeyen Select2 göstergesine yazmaya çalıştı:

```
1. Firma Fatura İşlemleri ekranını aç
2. Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç
3. Ödenecek durumundaki bir faturayı seç
4. İşlemler panelinden Fatura Güncelle'ye tıkla
5. Geri butonuna tıkla
```

| # | Kural | Kötü | İyi | Hangi kod mekanizması |
|---|---|---|---|---|
| 1 | Ekrandaki her ad çift tırnakta, ekranda yazdığı gibi | `Firma Seçimi alanından ASAŞ … seç` | `"Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç` | do_steps, ClaimCheck, liste kısaltma |
| 2 | Öğe türünü doğru söyle | `Fatura İptal Et butonuna` (aslında bağlantı) | `"Fatura İptal Et" bağlantısına` | Model doğru rolü arar |
| 3 | Her adımda tek eylem ve tek hedef | `Ödenecek durumundaki bir faturayı seç` | `Ödeme Durumu "Ödenecek" olan ilk satırı seç ve başka satır seçme` | `row` ilk eşleşeni alır; "bir" belirsizdir |
| 4 | "Ekranı aç" adımı yok | `Firma Fatura İşlemleri ekranını aç` | (yazma: prelude açtı) | Ajan menüye geri gider, sayfa büyür |
| 5 | Tırnaklı ada ek yapıştırma | `Fatura Güncelle'ye tıkla` | `"Fatura Güncelle" bağlantısına tıkla` | ClaimCheck tırnak arar; ek adı bozar |
| 6 | Onay sözcüklerini hiç yazma (olumsuz bile) | `Evet'e basma` | `yalnızca "Hayır" düğmesine tıkla` | Talimatta "evet" geçerse onay engeli onu **serbest bırakır** |
| 7 | Beklenen görüneni tırnakla söyler | `listeye dönülür` | `"Firma Fatura İşlemleri" listesi yeniden görünür` | Ajan neyi arayacağını bilir |

Şablon:

```
Ön koşul
- <firma/kullanıcı> için <durum> kayıt bulunmalı

Adımlar
1. "<Alan adı>" listesinden "<Seçenek>" seç
2. Izgarada <Sütun> "<Değer>" olan ilk satırı seç ve başka satır seçme
3. İşlemler panelindeki "<Bağlantı adı>" bağlantısına tıkla
4. Açılan <ekran> ekranında yalnızca "<Düğme adı>" düğmesine tıkla

Beklenen
- "<Ekran/liste adı>" yeniden görünür
```

**Bir koşu bozulduğunda önce senaryonun bu kurallara uyup uymadığına bak,
sonra koda.**

---

## 24.1 Kaydetmeden önce kontrol listesi

```
☐ Ekranda yazan her ad (alan, seçenek, düğme, bağlantı, sütun değeri) "çift tırnak" içinde
☐ Adlar ekranda yazdığı gibi, TAMAMIYLA ("Fatura İptal Et", kısaltılmış "İptal Et" değil)
☐ Tırnaklı adın arkasına ek yapışmamış ("Geri" düğmesine — Geri'ye değil)
☐ Her adımda tek eylem, tek hedef; "bir", "herhangi bir" yok — "ilk", "yalnızca" var
☐ Öğenin türü yazılmış: listesinden / bağlantısına / düğmesine / satırı / alanına
☐ "… ekranını aç", "… menüsüne git" gibi bir adım YOK
☐ Adımları sen numaralandırmadın, "Adımlar" başlığını adım olarak yazmadın
☐ Onay sözcükleri (aşağıdaki liste) hiçbir yerde geçmiyor — olumsuz cümlede ve başka kelimenin içinde bile
☐ Tarihler gg/aa/yyyy; tutarlar lira ve kuruş ayrı
☐ Beklenen, ekranda GÖRÜNECEK bir şeyi tırnakla söylüyor
☐ Ön koşul ortamda gerçekten sağlanıyor (ajan veri yaratmaz)
```

---

## 24.2 Öğe türü sözlüğü

Ajan, adımdaki türe bakarak sayfada **hangi rolü** arayacağına karar verir.
`do_steps` da aynı ayrımı yapar ([06-do-steps-ve-set-date.md](06-do-steps-ve-set-date.md)).

| Ekrandaki şey | Yazım kalıbı | do_steps karşılığı | Dikkat |
|---|---|---|---|
| Açılır liste (Select2) | `"<Etiket>" listesinden "<Seçenek>" seç` | `select` | Seçenek adı listede yazdığı gibi, noktası virgülü dahil ("ASAŞ ALÜMİNYUM SAN.") |
| Bağlantı (İşlemler paneli vb.) | `"<Ad>" bağlantısına tıkla` | `click` (link) | Bu ekranlarda "Fatura İptal Et", "Fatura Güncelle" bağlantıdır, düğme değil |
| Düğme | `"<Ad>" düğmesine tıkla` | `click` (button) | "Geri", "Hayır", "Sorgula" |
| Izgara satırı | `<Sütun> "<Hücre değeri>" olan ilk satırı seç ve başka satır seçme` | `row` | Hücre değeri hücrede yazdığı gibi, tam eşleşme. Birden çok satır eşleşirse ilki alınır |
| Yazı kutusu | `"<Etiket>" alanına "<değer>" yaz` | `type` | — |
| Tarih | `"<Etiket>" alanına "25/09/2026" tarihini gir` | `date` (`set_date`) | gg/aa/yyyy. "yarın", "+3 gün" gibi ifadeleri hesaplamak modele kalır, bu da tahmindir. Gherkin'deki `enter date` bunu çalışırken çözer, ajan çözmez |
| Tutar | `"<Etiket>" tutarına lira "1250", kuruş "50" gir` | (yok, ajan elle) | Asla "1.250,50" tek kutuya. Kutular ayrıdır |
| Sekme | `"<Ad>" sekmesine tıkla` | `click` (tab) | Liste bir sekmenin altındaysa bu adım şart, yoksa doğru satır yanlış listede seçilir |
| Onay kutusu | `"<Kutunun cümlesi>" kutusunu işaretle` | `click` (checkbox) | Cümle ekrandaki gibi, noktası dahil |
| Form içi radyo | `"<Seçenek>" seçeneğini seç` | `click` (radio) | — |

---

## 24.3 Onay sözcükleri: neden "Evet'e basma" bile yazılmaz

Bu sözcükleri taşıyan bir tıklama, senaryo o sözcüğü **içermiyorsa**
reddedilir (`BudgetedTool.COMMITTING`, [§10.4](04-araclar-ve-kapi.md)):

```
onayla   onay ver   evet   gönder   kaydet   sil   tamam
approve  confirm    yes    submit   delete
```

Kod senaryoda bir **anlam** aramaz, bir **harf dizisi** arar. Senaryonun
herhangi bir yerinde bu dizi geçtiği anda o sözcük serbest kalır:

| Senaryoda yazan | Serbest kalan | Sonuç |
|---|---|---|
| `"Evet" düğmesine basma` | evet | Ajan "Evet"e basarsa engel **durdurmaz** |
| `Beklenen: işlem tamamlandı` | tamam (t-a-m-a-m-l-a-n-d-ı) | "Tamam" onayı serbest |
| `"Gönderilen Faturalar" sekmesine tıkla` | gönder | "Gönder" düğmesi serbest |
| `Silinmiş kayıtlar görünmez` | sil | "Sil" düğmesi serbest |
| `Onaylanmış fatura seçilir` | onayla | "Onayla" serbest |

Bu yüzden:

- basılmaması gereken bir onayı **adıyla yazma**. Onun yerine basılacak olanı
  yaz: `yalnızca "Hayır" düğmesine tıkla`;
- bu harf dizilerini içeren kelimelerden kaçın ya da eşanlamlısını kullan
  ("tamamlandı" → "sonuçlandı", "Gönderilen Faturalar" sekmesi gerçekten
  gerekiyorsa adıyla yazmak zorundasın, o zaman bu riski bil);
- senaryo gerçekten onay istiyorsa (ör. bir kayıt oluşturma testi), onay
  düğmesini adıyla yaz. Engel o zaman bilerek açılır.

> Bilinen açık: kod bu kontrolü yalnız senaryoya değil, ajana giden ilk
> mesajın tamamına, yani ilk ekranın özetine de uyguluyor. İlk ekranda
> "Tamam" yazıyorsa senaryo yazmasa bile serbest kalabilir
> ([12-bilinen-aciklar.md](12-bilinen-aciklar.md), madde 1).

---

## 24.4 Kötü → iyi: yeniden yazım örnekleri

**Örnek 1: bant 2628c4fd'nin senaryosu** (sonuç "Geçti" çıktı ama iki adım
yapılmamıştı, [13-ornek-kosu-2628c4fd.md](13-ornek-kosu-2628c4fd.md)):

```
KÖTÜ                                                        NEDEN
1. Adımlar                                                  başlık adım olarak kaydedilmiş
2. 1. Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç        çift numara; adlar tırnaksız
3. 2. Listede Ödeme Durumu "Ödenecek" olan ilk faturanın    "listede" — hangi liste?
      satırını seç
4. 3. İşlemler panelindeki "Fatura İptal Et" düğmesine      bağlantı, düğme değil
      tıkla
5. 4. Açılan ekranda "Geri" düğmesine tıkla                 hangi ekran?
Beklenen: Liste ekranına dönülür                            görünecek şey adlandırılmamış
```

```
İYİ
1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
2. Izgarada Ödeme Durumu "Ödenecek" olan ilk satırı seç ve başka satır seçme
3. İşlemler panelindeki "Fatura İptal Et" bağlantısına tıkla
4. Açılan iptal ekranında yalnızca "Geri" düğmesine tıkla
Beklenen:
- "Firma Fatura İşlemleri" listesi yeniden görünür
- Seçilen faturanın Ödeme Durumu "Ödenecek" olarak kalır
```

**Örnek 2: bant a73ead34** (ASAŞ seçilemedi):

```
KÖTÜ                                                     İYİ
Firma Fatura İşlemleri ekranını aç                       (sil — ekranı kod açıyor)
Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç           "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
Ödenecek durumundaki bir faturayı seç                    Izgarada Ödeme Durumu "Ödenecek" olan ilk satırı seç ve başka satır seçme
İşlemler panelinden Fatura Güncelle'ye tıkla             İşlemler panelindeki "Fatura Güncelle" bağlantısına tıkla
Geri butonuna tıkla                                      Açılan güncelleme ekranında yalnızca "Geri" düğmesine tıkla
```

---

## 24.5 Ön koşul ve Beklenen ne işe yarar, ne işe yaramaz

| Bölüm | Ajana gider mi | Kod okur mu | Ne demek |
|---|---|---|---|
| Senaryo adı | Evet ("yalnızca bir isim, yapılacak bir adım DEĞİL" notuyla) | Token Takibi başlığı | Adım değildir |
| Ön koşul | Evet | Yalnız dolaylı: onay engeli ve liste kısaltma **bütün** metne bakar | Ajan veri **yaratmaz**, yalnız okur. Ön koşul ortamda sağlanmıyorsa koşu kırmızı çıkar ama hata uygulamada değildir |
| Adımlar | Evet, numaralı | ClaimCheck (yalnız numaralı basma adımları), liste kısaltma, PageChrome, onay engeli | Tırnaklı adlar burada işe yarar |
| Adımlar içinde `Beklenen:` satırı | Evet, numarasız, adımın altında | `StepRunner` partiyi o adımdan sonra durdurur; cache'li koşuda tırnaklı ad kodla aranır ([§30](14-adim-cache.md)) | Ara durumu (açılan pencere, çıkan mesaj) o adımdan **hemen sonra** kontrol ettirir |
| Beklenen | Evet, her madde B-numarasıyla "BEKLENEN LİSTESİ"nde | Her madde için ajandan GÖRÜLDÜ / GÖRÜLMEDİ ister; cevapsız madde **Geçmedi**. Adları son ekranda yok ama önceki bir ekranda varsa **Geçmedi** ("kontrol edilemedi", [§17](07-sonuc-kayit-gherkin.md)). Tırnaklı bir ad ya da "… alanları" listesindeki bir ad koşunun hiçbir ekranında yoksa **Geçmedi** ("görülmedi") "Gherkin oluştur" THEN satırlarını buradan üretir | Görüp görmediğine yine ajan karar verir. Görünür ve tırnaklı yaz ki yanlış karar zorlaşsın. **Son ekranda olmayan bir şeyi buraya yazma** |

**Bir adımın sonucunu o adımın altına yazın.** Sondaki Beklenen alanı ancak
son ekranı görür. "Fatura İptal Et"in açtığı pencere, "Geri"ye basıldıktan
sonra artık yoktur:

```
"Fatura İptal Et" bağlantısına tıkla
Beklenen: "Fatura İptal" ekranı açılır
"Geri" düğmesine tıkla
```

Aynı şey alanlar için de geçerli. Gerçek örnek (bant `bd40c3ee`): son adım
"Geri" iken sondaki Beklenen'e güncelleme ekranının alanları yazılmıştı. Son
ekran liste olduğu için bu madde kontrol edilemez ve kod **Geçmedi** sayar.
Doğrusu, maddeleri o ekranı açan adımın altına yazmak:

```
İşlemler panelindeki "Fatura Güncelle" bağlantısına tıkla
Beklenen: "Fatura Güncelle" ekranı açılır; üstteki "Kurum Adı", "Bayi Kodu", "Fatura No" düz metin olarak görünür
Beklenen: "Yeni Fatura No", "Yeni Fatura Tutarı" alanları düzenlenebilir kutulardır
Açılan güncelleme ekranında yalnızca "Geri" düğmesine tıkla
```

Sondaki Beklenen alanına yalnız Geri'nin sonucu yazılır: `"Firma Fatura
İşlemleri" listesi yeniden görünür`. Yer tutucu yazmayın
(`"<güncelleme ekranının başlığı>"`): ekranın gerçek başlığını yazın, yoksa
madde hiçbir şeyi ölçmez.

Ekran Keşfi'ndeki `TIKLAMA:` / `ZORUNLU:` satırları burada **çalışmaz**:
senaryo metni numaralandırıldığı için satır başında durmazlar.
