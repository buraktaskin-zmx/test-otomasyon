# Firma Fatura İşlemleri — senaryo ön koşulları

66 senaryonun her biri için koşmadan önce ortamda hazır olması gerekenler.
Senaryo numaraları dışa aktarılan dosya adlarındaki numaralardır
(`firma-fatura-islemleri/NN-....feature`). Kural kimlikleri
[`rules.yaml`](rules.yaml) içinde.

Bir ön koşul sağlanmamışsa senaryo düşer ve bu bir uygulama hatası değildir;
önce ön koşula bakın.

## Bütün senaryolar için

- Test kullanıcısı **QZ06395288** ile giriş yapılabilmeli ve NAKİT YÖNETİMİ →
  Firma Fatura İşlemleri menüsüne erişimi olmalı.
- Firma Seçimi listesinde **APPLE A.Ş.**, **Sanko DBS Ödemeleri** ve
  **ASAŞ ALÜMİNYUM SAN.** bulunmalı.

## Tarihe bağlı veri — en sık düşme sebebi

Firma seçilince sorgu, Hızlı Tarih Seçimi'nin **varsayılanı olan Bu Hafta**
aralığıyla çalışıyor. 07.11.2026'da bu aralık 02/11/2026 – 09/11/2026'ydı;
APPLE A.Ş. için yalnızca son ödeme tarihi bu haftaya düşen iki fatura geldi
(bel1, belge1). Bu yüzden:

- Bir senaryo belirli bir faturayı seçiyorsa (ccsdf, anilbelge2, HLLSRC03…),
  o faturanın **son ödeme tarihi koşulan haftaya düşmeli**, ya da senaryo önce
  daha geniş bir Hızlı Tarih Seçimi / tarih aralığı seçmeli.
- ccsdf faturasının son ödeme tarihi 29/10/2026 — bugünkü "Bu Hafta"
  aralığının dışında. Senaryo 07 bu haliyle bu satırı bulamaz.
- "Bu Ay (Ekim)" gibi bir seçenek her ay adını değiştirir; senaryo 10 Kasım'da
  bu seçeneği bulamaz.

## Yetki — kullanıcıya bağlı

- QZ06395288 kullanıcısının **Fatura Güncelle** yetkisi yok: ajan
  "Bu işlemi yapmaya yetkiniz yoktur." uyarısını aldı (R-FF-080). 10, 55, 56
  bu kullanıcıyla koşamaz; büyük ihtimalle **Fatura İptal Et** (57, 58) için de
  aynısı geçerli, teyit edilmedi.
- **Sanko DBS Ödemeleri**'nde fatura oluşturma yürüyüşü yapılabildi; oluşturma
  senaryoları bu firmayla yazıldı.

## Senaryo bazında

| # | Senaryo | Kural | Ön koşul |
|---|---|---|---|
| 01 | bayi kodu dogrulama | R-FF-032 | APPLE A.Ş.'nin 151732 bayi kodlu, son ödeme tarihi bu haftaya düşen bir faturası olmalı. **39 ile aynı senaryo.** "cif1 numaralı fatura" ekranda yok; fatura numarası düzeltilmeli. |
| 02 | fatura oluştur | R-FF-050 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi; bayisanko1 bayisi seçilebilmeli. Başlangıç Vadesi **28/10/2026 artık geçmiş** — R-FF-053 gereği reddedilir, ileri bir tarih yazılmalı. Fatura No 11113 daha önce kullanılmamış olmalı (tekrar koşuda benzersizlik kuralı varsa düşer). **09 ile aynı senaryo.** |
| 03 | Min maks tutar doğrulama | R-FF-010 | APPLE A.Ş. seçilebilmeli. Veri gerekmez. **18 ile aynı senaryo.** |
| 04 | Başlangıç tarihi bitiş tarihinden büyük olamaz | R-FF-011 | APPLE A.Ş. seçilebilmeli. Veri gerekmez. **19 ile aynı senaryo.** |
| 05 | Detaylı arama kapatılabilir | R-FF-004 | Yok (ortak ön koşul yeterli). |
| 06 | Firma Seçimi listesinde arama yapılır | R-FF-006 | Adında "san" geçen en az bir firma (Sanko DBS Ödemeleri) listede olmalı. |
| 07 | Fatura detayı kapatılabilir | R-FF-032 | APPLE A.Ş.'nin ccsdf faturası **listede görünmeli** — son ödeme tarihi 29/10/2026, "Bu Hafta" dışında (yukarıya bakın). |
| 08 | Zorunlu alanlar boş → Devam | R-FF-048 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. Veri gerekmez. **51 ile aynı senaryo.** |
| 09 | Bilgi girişi ile fatura oluşturulur | R-FF-050 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi; bayisanko1 bayisi seçilebilmeli (Adımlar "151732" diyor, o APPLE'ın bayisi — Gherkin bayisanko1 seçiyor). Başlangıç Vadesi 27/12/2026 o tarihe kadar ileri tarih. Fatura No 11113 benzersiz olmalı. |
| 10 | Güncelleme ekranı mevcut bilgileri salt okunur gösterir | R-FF-060 | **Fatura Güncelle yetkisi olan bir kullanıcı.** ASAŞ ALÜMİNYUM SAN.'nin Ödenecek durumunda, listede görünen bir faturası (HLLSRC03) olmalı; "Bu Ay (Ekim)" seçeneği Kasım'da yok. |
| 11 | Yetkili firmada oluşturma tipi ekranı açılır | R-FF-041 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 12 | Ekran açıldığında hızlı filtre alanları görünür | R-FF-001, R-FF-002 | Yok. |
| 13 | Firma seçilmeden tablo boş gelir | R-FF-003 | Yok. |
| 14 | Detaylı arama alanları açılır | R-FF-004 | Yok. |
| 15 | Filtre alanlarının varsayılan değerleri gelir | R-FF-005 | Yok. Kullanıcıya kayıtlı bir varsayılan filtre tanımlı olmamalı. |
| 16 | Hızlı tarih seçimi tarih alanlarını doldurur | R-FF-007 | Yok. Gherkin'deki 02/11/2026 – 09/11/2026 yalnızca **o hafta** doğru; her hafta değişir. |
| 17 | Tarih kriteri değiştirilebilir | R-FF-008 | Yok. |
| 18 | Minimum tutar maksimumdan büyük olamaz | R-FF-010 | APPLE A.Ş. seçilebilmeli. |
| 19 | Başlangıç tarihi bitiş tarihinden büyük olamaz | R-FF-011 | APPLE A.Ş. seçilebilmeli. |
| 20 | Firma seçilince sorgu otomatik çalışır | R-FF-012 | APPLE A.Ş.'nin son ödeme tarihi bu haftaya düşen en az bir faturası. |
| 21 | Listele butonu sorguyu yeniler | R-FF-012 | APPLE A.Ş.'nin son ödeme tarihi bu haftaya düşen en az bir faturası. |
| 22 | Fatura numarası ile sorgulanır | R-FF-013 | APPLE A.Ş.'de anilbelge2 faturası; tarih aralığı bu faturayı kapsamalı. |
| 23 | Tutar aralığı filtresi çalışır | R-FF-010 | APPLE A.Ş.'de aynı tarih aralığında 100–200 TL arasında **ve** dışında en az birer fatura (yoksa filtrenin bir şey ayıkladığı görülmez). |
| 24 | Para birimi filtresi çalışır | R-FF-014 | ASAŞ ALÜMİNYUM SAN.'de aynı tarih aralığında TL **ve** TL dışı en az birer fatura. |
| 25 | Statü filtresi liste sonucunu daraltır | R-FF-015 | ASAŞ ALÜMİNYUM SAN.'de aynı tarih aralığında Ödenecek **ve** başka statüde en az birer fatura. |
| 26 | Bayi kodu filtresi çalışır | R-FF-016 | APPLE A.Ş.'de 151732 **ve** başka bir bayi koduna ait birer fatura; 151732 Bayi Kodu listesinde olmalı. |
| 27 | Sonuç bulunmayan sorguda bilgi mesajı çıkar | R-FF-017 | Sanko DBS Ödemeleri'nde 00000000 numaralı fatura **olmamalı**. |
| 28 | Firma değişince önceki sonuçlar temizlenir | R-FF-012 | APPLE A.Ş.'nin bu hafta faturası olmalı (belge1); Sanko DBS Ödemeleri'nin bu hafta faturası **olmamalı** (Kayıt bulunamadı. görülür). |
| 29 | Özet bandı firma bilgilerini gösterir | R-FF-020 | APPLE A.Ş.'nin bu hafta faturası; Firma Kodu APPYAKOPS. |
| 30 | Çok para birimli özet ayrı satırlarda görünür | R-FF-021 | ASAŞ ALÜMİNYUM SAN.'de aynı tarih aralığında TL, USD ve EUR faturaları. |
| 31 | Tablo kolonları görünür | R-FF-022 | APPLE A.Ş.'nin bu hafta en az bir faturası (boş tabloda başlıklar yine görünüyor olabilir, teyit edilmedi). |
| 32 | Kolon başlığına tıklanınca sıralama yapılır | R-FF-023 | APPLE A.Ş.'de aynı aralıkta **farklı tutarlı** en az iki fatura (bel1 226,50 TL, belge1 33,00 TL yeter). |
| 33 | Tablo içinde bul alanı kayıt süzer | R-FF-024 | APPLE A.Ş.'de anilbelge2 ve en az bir başka fatura aynı aralıkta listelenmeli. |
| 34 | Negatif tutarlar ayırt edilebilir görünür | R-FF-025 | ASAŞ ALÜMİNYUM SAN.'de aynı aralıkta negatif tutarlı bir fatura. |
| 35 | Dışa aktarma bağlantıları bulunur | R-FF-026 | APPLE A.Ş.'nin bu hafta en az bir faturası. |
| 36 | Satır seçilmeden İşlemler panelinde üç seçenek bulunur | R-FF-030 | APPLE A.Ş.'nin bu hafta en az bir faturası. |
| 37 | Satır seçilince üç seçenek daha görünür | R-FF-031 | ASAŞ ALÜMİNYUM SAN.'nin listede görünen Ödenecek bir faturası. |
| 38 | Aynı anda tek satır seçilebilir | R-FF-031 | APPLE A.Ş.'de aynı aralıkta en az iki fatura (bel1, belge1). |
| 39 | Fatura detayında bayi kodu doğrulanır | R-FF-032 | APPLE A.Ş.'nin 151732 bayi kodlu, listede görünen bir faturası. |
| 40 | Fatura detayı tüm alanları gösterir | R-FF-032 | APPLE A.Ş.'de anilbelge2: bayi 151732, E-Fatura, 111,20 TL, Ödenecek; tarih aralığı kapsamalı. Fatura ödenirse ya da durumu değişirse senaryo düşer. |
| 41 | E-Fatura tipinde hash kodu görünür | R-FF-033 | APPLE A.Ş.'de listede görünen E-Fatura tipinde bir fatura (anilbelge2) ve hash kodu dolu. |
| 42 | Detaydaki değerler tablo satırıyla uyuşur | R-FF-032 | APPLE A.Ş.'de anilbelge2 listede görünmeli. |
| 43 | Yetkisiz firmada fatura oluşturulamaz | R-FF-040 | **APPLE A.Ş.'de fatura oluşturma yetkisi olmamalı** — teyit edilmedi; yetki varsa senaryo yanlış firmayla yazılmıştır. |
| 44 | Oluşturma tipi seçilmeden form açılmaz | R-FF-041 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 45 | Bilgi girişi formu alanları görünür | R-FF-042 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 46 | Döviz cinsi yalnızca TL seçilebilir | R-FF-043, R-FF-052 | Sanko DBS Ödemeleri'nde yetki; seçenekler bayiye bağlı yükleniyor (R-FF-052), önce bir bayi seçilmeli. |
| 47 | Bayi kodu listesi açılır | R-FF-044 | Sanko DBS Ödemeleri'ne tanımlı en az bir bayi (bayisanko1). |
| 48 | Taksit periyot birimi seçenekleri listelenir | R-FF-045 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 49 | Fatura tipi seçenekleri listelenir | R-FF-046 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 50 | Tutar alanları lira ve kuruş olarak ayrıdır | R-FF-047 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 51 | Zorunlu alanlar boş bırakılınca devam edilemez | R-FF-048 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 52 | Ek bilgi satırı eklenebilir | R-FF-049 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 53 | Ek bilgi satırı silinebilir | R-FF-049 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 54 | Oluşturma ekranından Geri ile listeye dönülür | R-FF-041 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 55 | Güncelleme formunda yeni değer alanları bulunur | R-FF-061 | **Fatura Güncelle yetkisi olan bir kullanıcı**; ASAŞ ALÜMİNYUM SAN.'nin listede görünen Ödenecek bir faturası. |
| 56 | Güncelleme ekranından Geri ile listeye dönülür | R-FF-060 | 55 ile aynı. |
| 57 | İptal akışında fatura bilgileri görünür | R-FF-070 | **Fatura İptal Et yetkisi olan bir kullanıcı**; ASAŞ ALÜMİNYUM SAN.'nin listede görünen Ödenecek bir faturası. |
| 58 | İptal ekranından Geri ile listeye dönülür | R-FF-070 | 57 ile aynı. Senaryo iptali **onaylamamalı** — onaylanırsa fatura bir daha kullanılamaz. |
| 59 | Bayi kodu seçilince bayi ünvanı kendiliğinden dolar | R-FF-051 | Sanko DBS Ödemeleri'ne tanımlı bayi. Adımlar "151732" diyor; Sanko'nun bayisi bayisanko1. |
| 60 | Bayi kodu seçilmeden döviz cinsi seçenek sunmaz | R-FF-052 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 61 | Başlangıç vadesi bugün olamaz | R-FF-053 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 62 | Tutar girilmeden devam edilemez | R-FF-048 | Sanko DBS Ödemeleri'nde yetki; bayisanko1 ve diğer zorunlu alanların geçerli değerleri. Mesaj ekranda "Toplam tutar giriniz." (noktalı). |
| 63 | Tutar tek kutuya biçimli yazılınca kabul edilmez | R-FF-047 | 62 ile aynı. |
| 64 | E-Fatura tipi seçilince hash kodu alanı açılır | R-FF-054 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 65 | Dosya yükleme tipi ayrı bir ekran açar | R-FF-055 | Sanko DBS Ödemeleri'nde fatura oluşturma yetkisi. |
| 66 | Onay adımından geri dönülünce girilen değerler korunur | R-FF-056 | Sanko DBS Ödemeleri'nde yetki; bayisanko1, ileri bir Başlangıç Vadesi ve benzersiz Fatura No. |

## Aynı işi yapan senaryolar

Eski elle yazılmış dört senaryo ile listedeki karşılıkları ikişer kez var:
01 ↔ 39, 02 ↔ 09, 03 ↔ 18, 04 ↔ 19; ayrıca 08 ↔ 51. Birini tutup diğerini
silmek koşu süresini kısaltır ve iki yerde düzeltme yapılmasını önler.
