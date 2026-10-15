# Sunum Bağlamı — AI Destekli Test Otomasyonu

Bu dosya **sunum hazırlamak için** yazıldı. Projeyi hiç bilmeyen birine
anlatacak şekilde, sıfırdan. Depodaki diğer `CLAUDE.md` dosyaları geliştirme
sırasında yapay zekâya verilen çalışma talimatlarıdır; bu dosya onlardan farklı
olarak **anlatıdır**.

Kurum: Yapı Kredi. Hedef uygulama: kurumsal internet bankacılığı (NGC) ve
bireysel internet bankacılığı (NGI).

---

## 0. Bir cümlede

İş analistlerinin kod yazmadan, tarayıcıdan, doğal dille test senaryosu üretip
çalıştırabildiği bir platform — üretimi bir dil modeli yapıyor, ama modelin
uydurabileceği alan bilinçli olarak sıfıra indirilmiş.

---

## 1. Problem

Ekip daha önce **tamamen manuel** test ediyordu. BDD altyapısı yoktu.

Bir regresyon senaryosunu çalıştırmak için gereken şeyler: bir IDE, Maven, VDI
erişimi, Playwright tarayıcıları, ve Java bilgisi. Yani test yazabilen tek grup
geliştiricilerdi. İşin kuralını bilen iş analisti ise senaryoyu ancak tarif
edebiliyor, çalıştıramıyordu.

Bunun üç bedeli vardı:

- **Zaman.** Bir senaryo saatler alıyordu.
- **Kapsam.** Pahalı olduğu için sınır durumlar yazılmıyordu.
- **Darboğaz.** Kuralı bilen kişi ile testi yazabilen kişi farklıydı.

---

## 2. Çözümün şekli

Üç katman, ve aralarındaki sınır projenin en önemli tasarım kararı:

```
.feature dosyası (Gherkin)      ← YAPAY ZEKÂ yalnızca burayı üretir
────────────────────────────────────────────────────────────
adım tanımları (step definitions)  ← insan yazar, sabit
sayfa nesneleri (page objects)     ← insan yazar, karmaşıklık burada
────────────────────────────────────────────────────────────
gerçek banka ekranı (DOM)
```

**Model yalnızca senaryo üretir; kod üretmez.** Adım tanımları ve sayfa
nesneleri insan yazımıdır ve sabittir.

Bu kısıt tesadüf değil, güvenin kaynağı: üretilen her test, daha önce
**çalıştığı kanıtlanmış** adımların üzerine oturuyor. Model yeni bir tıklama
yöntemi icat edemez, çünkü elinde yalnızca var olan fiiller var.

---

## 3. Sistem uçtan uca nasıl çalışıyor

```
1. Analist tarayıcıda ekranı ve ne test etmek istediğini seçer
2. Model, EKRAN KATALOĞU + ADIM SÖZLÜĞÜ + analistin isteğinden Gherkin üretir
3. Doğrulayıcı üretilen senaryoyu denetler       ← tarayıcı açılmadan, ~50 ms
4. Analist metni gözden geçirir, isterse düzenler
5. "Çalıştır" → senaryo boş bir executor'a dağıtılır
6. Gerçek tarayıcı açılır, gerçek test ortamında koşar
7. Adım adım ilerleme canlı izlenir
8. Sonuç, rapor ve Playwright trace'i saklanır
```

Üçüncü adım kritik: model hatalı bir adım yazdıysa ya da analist yanlış bir şey
düzenlediyse, bu **dört dakikalık bir tarayıcı koşusundan önce** yakalanır.

---

## 4. Kavramlar — sıfırdan

### 4.1 Gherkin ve `.feature` dosyası

Gherkin, testi neredeyse düz İngilizce yazmaya yarayan bir dil. Bir senaryo
böyle görünür:

```gherkin
Feature: Banko Ödeme Talebi

  Scenario: Kullanıcı Banko ekranından ödeme talep eder
    Given login with cif "DI00002018" and userid "3014"
    And navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"
    And click "Ödeme Talep Et"
    And enter amount "Ödeme Tutarı" "1250,55"
    And select account "0006 7010 0000 0003 4322" from "Alıcı Hesabı"
    And click "Devam"
    Then verify the "Ödeme Tutarı" has amount "1250,55"
```

Bunu bir iş analisti okuyabilir ve düzeltebilir. Testin değerli tarafı da bu:
**okunabilir kalması.**

### 4.2 Adım sözlüğü (vocabulary) — ve neden kapalı

Yukarıdaki her satır, önceden yazılmış bir kalıba karşılık gelir. Şu an **17
kalıp** var: `login`, `navigate to`, `click`, `enter`, `enter amount`,
`select`, `select account`, `select row`, `verify ... equals`,
`verify ... has amount`, `verify ... text is displayed` ve birkaç varyantı.

Model bu listenin dışına **çıkamaz**. Çıkarsa doğrulayıcı reddeder.

Önemli ayrıntı: bu liste elle tutulmuyor. Derleme sırasında
`VocabularyExtractor` kodun içindeki `@Given/@When/@Then` anotasyonlarını
okuyup `vocabulary.json` üretiyor, ve doğrulayıcı bunu **ağ üzerinden, çalışan
sistemden** çekiyor.

Neden böyle: elle tutulan bir liste er geç koddan sapar. Sapınca da doğrulayıcı
"geçerli" dediği bir senaryoyu çalıştırır ve test "tanımsız adım" diye patlar.
Tek kaynak, sapma yok.

### 4.3 Katalog — ekranın sözlüğü

Model bir senaryo yazabilmek için ekranda **ne olduğunu** bilmeli: alan
etiketleri, tipleri, açılır listelerin seçenekleri, oraya nasıl gidildiği.

Katalog bunu tutan YAML dosyası:

```yaml
app: NGC
flow: Online DBS Fatura Oluştur

navigation:
  main_menu: "NAKİT YÖNETİMİ"
  sub_menu: "Online DBS İşlemleri"
  entry_row: "FORD DBS ODEMELERI"
  entry_button: "Fatura Oluştur"

fields:
  - label: "Bayi Kodu"
    type: dropdown
    options: ["93002"]
    verifiable: true
  - label: "Bayi Ünvanı"
    type: text
    readonly: true          # Bayi Kodu seçilince uygulama kendisi doldurur
  - label: "Başlangıç Vadesi"
    type: date
```

**Katalog test verisi içermez.** İçinde "1250,55" ya da bir müşteri numarası
yoktur. Bir senaryo şu üçünün toplamıdır:

> **katalog** (isimler) + **analistin isteği** (fiiller ve değerler) +
> **sözlük** (dilbilgisi)

Bu yüzden **tek katalog, sayısız senaryoya** hizmet eder. Analist veriyi
değiştirir, kataloğa dokunmaz.

Kataloğun ikinci işlevi: doğrulayıcı üretilen senaryoyu **ona karşı** denetler.
Katalogda olmayan bir alan adı, sunulmayan bir seçenek, tipine uymayan bir fiil
— hepsi yakalanır.

### 4.4 Katalog nasıl üretiliyor

Kataloğu elle yazmak mümkün ama yavaş ve hataya açık. Üreten şey **MCP ajanı**:
bir dil modeli gerçek tarayıcıyı sürüyor, akışı yürüyor, her alanı incelediği
anda kaydediyor, onay ekranını okuyor ve sonuçtaki başarı mesajını alıyor.
Analist taslağı arayüzden doğrudan `catalogs/` altına kaydediyor ve senaryo
üreticisi onu anında listesinde görüyor.

**İkinci bir üretici** de var: executor içinde çalışan, model kullanmayan
deterministik bir DOM taraması. Ajanın göremediği şeyleri görüyor — bir açılır
listenin sürülebilir Select2 v4 mü yoksa sürülemeyen v3 mü olduğunu (ikisi
ekranda birebir aynı görünür), bir tutar alanının iki ayrı input olduğunu, ve
alanlar arası bağımlılıkları ekranın öncesi/sonrasını karşılaştırarak
**ölçüyor**. Orchestrator bunu sentetik bir "giriş yap, git, keşfet" feature'ı
olarak kurup normal bir koşu gibi executor'a gönderiyor.

İkisi de duruyor çünkü kör noktaları birbirinin tersi: tarama son butona hiç
basmadığı için başarı mesajına ulaşamıyor, ajan da Select2 sürümünü ayırt
edemiyor. Yeni bir ekranda ikisini birden koşturup farkı okumak, tek tek
güvenmekten daha iyi sonuç veriyor — "Ek Bilgi" alanı ve atlanmış bir
`verifiable` tam böyle bulundu.

**Kural değişmedi: hiçbir çıktı, bir insan bakmadan katalog sayılmaz.** Ajan
tekrarlanabilir değil — aynı ekran bir koşuda "Ay", başka koşuda
"Ay, Hafta, Gün" verdi. Mekanik olarak denetlenebilen şeyleri
`ExploreController.problems()` taslakla birlikte raporluyor: hiçbir adımın
süremeyeceği bir tip, seçeneksiz bir açılır liste, arkasında `verifiable`
olmayan bir `success_message`.

### 4.5 MCP nedir, neden eklendi

**MCP (Model Context Protocol)**, bir dil modeline dış dünyada iş yaptırmak için
kullanılan açık bir protokol. Model "şu düğmeye tıkla" diye bir **araç çağrısı**
üretir, protokolün diğer ucundaki sunucu bunu gerçekten yapar ve sonucu geri
verir.

Burada kullanılan `@playwright/mcp`: modele `browser_navigate`, `browser_click`,
`browser_type`, `browser_snapshot` gibi araçlar veriyor. Model gerçek bir Edge
penceresini sürüyor.

**Neden eklendi:** deterministik tarama ekranı okuyor ama akışı
**yürüyemiyor**. Bir ekrana nasıl gidildiğini, hangi düğmenin hangi ekranı
açtığını, ve işlem tamamlanınca ne yazdığını ancak gerçekten oraya giden bir şey
öğrenebilir. Başarı mesajı bunun en net örneği: forma bakarak asla öğrenilemez,
ve o mesaj olmadan hiçbir senaryo işlemin gerçekten tamamlandığını kanıtlayamaz.

İkinci sebep daha stratejik: analistin konuşarak ekran keşfi yapabilmesi.

### 4.6 Doğrulayıcı

Üretilen senaryoyu, tarayıcı açılmadan denetler. İki sınıf kontrol:

- **Dilbilgisi.** Gerçek Cucumber ayrıştırıcısı kullanılıyor — elle yazılmış
  regex değil. Böylece satır numaraları ve kabul/ret kararı, Cucumber'ın
  kendisinin vereceği kararla birebir aynı.
- **Anlam (katalog farkındalığı).** Katalogda olmayan alan adı; alanın tipine
  uymayan fiil (tarih alanına `select`); sunulmayan seçenek; senaryonun hiç
  doldurmadığı bir alan için uydurulmuş bir beklenen değer; birebir
  kopyalanması gereken onay mesajının **yeniden yazılmış** hali; hiçbir adımda
  kullanılmayan bir `Examples` tablosu.

Son üçü gerçek koşularda yakalandı. Model, katalogda olmayan bir bayi ünvanını
uydurmuştu ve başarı mesajını kendi cümleleriyle yeniden yazmıştı — ikisi de
sessizce geçseydi test yanlış şeyi doğrulardı.

### 4.7 Orchestrator ve Executor

İki ayrı çalışan uygulama:

| | orchestrator | executor |
|---|---|---|
| ne yapar | arayüz, doğrulama, LLM, dağıtım, sonuç toplama | Cucumber + Playwright'ı koşturur |
| tarayıcı açar mı | **asla** | tek işi bu |
| analist görür mü | evet, tek gördüğü | hayır, hiç |
| kaç adet | 1 | 3 kopya |
| port | 8035 (dışarı açık) | 9035 (yalnızca iç ağ) |

**Neden ayrıldılar — iki ölçülmüş sebep:**

1. **Boyut.** Playwright'ın tarayıcı sürücüsü 236 MB. Executor imajı **4.97 GB**,
   orchestrator imajı **444 MB**. Tek uygulama olsalardı arayüzü sunan taraf da
   hiç kullanmayacağı sürücüyü taşırdı.
2. **Asılan koşu.** Takılmış bir Playwright çağrısı **Java'dan öldürülemiyor** —
   thread native kodda bloke oluyor, `interrupt()` ulaşmıyor, hiçbir şey
   fırlatılmıyor. Tek çare süreci yeniden başlatmak. Ayrı olduklarında takılan
   executor yeniden başlatılır, diğer ikisi çalışmaya devam eder, **arayüz hiç
   kesilmez.**

### 4.8 Paralel çalışma — kapasite nasıl hesaplanıyor

**Her executor aynı anda tek koşu alır.** Sebebi teknik ve pazarlıksız:
Cucumber statik durum taşıyor ve Playwright'ın tarayıcı nesnesi paylaşılıyor.
Aynı JVM'de iki koşu birbirini bozar, ve bu bozulma çöküş olarak değil
**kararsız test** olarak görünür — analistin platforma güvenini bitiren şey de
tam olarak budur. Bu yüzden ölçek, sayıyı artırarak değil **kopya ekleyerek**
büyür.

Yani: **kapasite = executor kopya sayısı.** Üç kopya, üç eşzamanlı koşu.

**Kuyruk yoktur.** Üçü de doluyken gelen istek anında reddedilir:

| aynı anda gönderilen | 3 executor | 1 executor (yerel) |
|---|---|---|
| 1. | koşar | koşar |
| 2., 3. | koşar | reddedilir |
| 4.–10. | reddedilir | reddedilir |

Dağıtım zinciri: istek gelir → doğrulanır → boş executor aranır, sırayla
denenerek (round-robin) → kabul eden ilkine verilir → hiçbiri kabul etmezse
analiste "hepsi meşgul" döner.

Neden "önce boş olanı sor, sonra ona gönder" değil: bu iki çağrı arasında başka
bir istek o düğümü kapabilir — klasik yarış durumu. Bunun yerine doğrudan
denenir; meşgul executor zaten reddeder, kaybedilen yarışın bedeli bir sonraki
düğümü denemek olur.

Neden kuyruk yok: sessizce kuyruğa alınan bir koşu analiste ne zaman
başlayacağını **söyleyemez**. "Kabul edildi" deyip başlamamış olmak, "şimdi
olmaz, tekrar dene" demekten kötüdür. Kuyruk ileride istenirse orchestrator'a
eklenir — executor'a değil, çünkü executor takıldığında yeniden başlatılan
süreçtir ve elindeki kuyruğu da kaybeder.

**Ayrıca her koşu birbirinden yalıtılmış:** her koşu için yeni tarayıcı
bağlamı (çerez ve oturum sızmaz) ve kendi çalışma dizini — `runs/{runId}/`.
Hiçbir şey sabit bir yola yazmıyor, yoksa eşzamanlı iki koşu birbirinin
raporunun üstüne yazardı.

---

## 5. Teknoloji yığını

Her satırın bir gerekçesi var; sunumda "ne kullandık" değil "neden bunu
kullandık" tarafı ilginç olan.

| katman | teknoloji | neden |
|---|---|---|
| test dili | **Cucumber / Gherkin** | senaryo iş analistinin okuyup düzeltebileceği bir metin olarak kalsın |
| tarayıcı sürücüsü | **Playwright (Java)** | otomatik bekleme (auto-wait) yerleşik; sabit `sleep` yazmadan kararlı test. Ayrıca trace kaydı hata ayıklama için birinci sınıf |
| dil / çalışma zamanı | **Java 17+, Spring Boot 3** | kurumun mevcut yığını; ayrı bir teknoloji öğrenilmesi gerekmiyor |
| iki servis | **Spring Web (REST)** | orchestrator ↔ executor arası tek bağ; Maven bağımlılığı bilinçli olarak yok |
| LLM entegrasyonu | **Spring AI 2.0** | OpenAI uyumlu protokol; model değiştirmek tek satır yapılandırma |
| model | **banka içi vLLM** (Qwen 3.6 35B) | veri kurum dışına çıkmıyor |
| ajan araçları | **MCP — Model Context Protocol**, `@playwright/mcp` | modele gerçek tarayıcı sürdürmenin standart yolu |
| Gherkin doğrulama | **io.cucumber gherkin + cucumber-expressions** | kabul/ret kararı ve satır numaraları Cucumber'ın kendi kararıyla birebir aynı olsun; elle regex yazmak sapma demek |
| katalog formatı | **YAML (snakeyaml)** | insan okuyup düzeltebilsin |
| paketleme | **Docker / Docker Compose** | 1 orchestrator + 3 executor, tek komutla |
| arayüz | **düz HTML/CSS/JS** | çerçeve yok; analistin ihtiyacı olan ekran küçük, bağımlılık taşımaya değmez |
| derleme | **Maven, çok modüllü** | iki modül tek depoda; birlikte derlenir, ayrı çalışır |

Dikkate değer bir bulgu: **araç kullanan bir ajan için model seçmek, model
seçmekten ibaret değil.** Modelin sunucu tarafında doğru araç-çağrısı
ayrıştırıcısıyla servis edilmiş olması gerekiyor. Aynı model iki farklı uçtan
sunulduğunda biri araç çağrılarını doğru ayrıştırdı, diğeri hiç ayrıştırmadı ve
çağrılar düz metin olarak cevaba düştü. Model sabit, uç değişken — yani sorun
modelin yeteneği değil, dağıtımın yapılandırmasıydı.

## 6. Model nerede çalışıyor — banka içi

Kullanılan dil modeli bankanın **kendi altyapısında** koşan bir vLLM
dağıtımında. Veri kurum dışına çıkmıyor. Bu bir bankacılık projesinde
tartışılmaz bir gereklilik.

Bağlantı OpenAI uyumlu protokolle kuruluyor (Spring AI), yani model
değiştirmek tek satırlık bir yapılandırma değişikliği.

Buna bağlı, sunuma değer bir bulgu: model değiştirmek göründüğü kadar
serbest değil. Araç kullanan bir ajan için modelin **sunucu tarafında doğru
ayrıştırıcıyla servis edilmiş** olması gerekiyor. Aynı model iki farklı uçtan
sunulduğunda biri araç çağrılarını doğru ayrıştırdı, diğeri hiç ayrıştırmadı ve
çağrılar düz metin olarak cevaba düştü. Model sabit, uç değişken — yani suçlu
modelin yeteneği değil, dağıtımın yapılandırmasıydı.

---

## 7. Gerçek mühendislik problemleri

Sunumda "yapay zekâ ile test üretiyoruz" cümlesinin arkasındaki asıl iş bunlar.
Hepsi gerçek koşularda yaşandı ve ölçülerek çözüldü.

**Sessizce geçen test — en pahalısı.** Metin doğrulama fonksiyonu değerleri
normalize ediyordu; normalize etmek `"Test"` değerini `""`e çeviriyordu ve
karşılaştırma `"" == ""` olduğu için **her metin doğrulaması sahte geçiyordu.**
İki gün boyunca bütün testler yeşildi ve hiçbiri bir şey doğrulamıyordu.
Çıkarılan kural: metin karşılaştırması birebir olur, normalize edilmez.

**Maskeli alanlar.** Tarih kutuları değeri tuş olaylarından yeniden kuruyor.
Playwright'ın `fill()` çağrısı değeri tek olayda yazıyor, maske onu yok sayıp
boş maskeyi geri boyuyor — ve `fill()` "başardım" diyor. Adım yeşil, alan boş.
Çözüm: takvim üzerinden seçmek, olmazsa gerçek tuş olayları göndermek, o da
tutmazsa **yüksek sesle patlamak.** Sessiz geçiş, başarısızlıktan kötüdür.

**İki farklı Select2.** Ekranda birebir aynı görünen iki açılır liste
bileşeninden yalnızca biri sürülebiliyor. Gizli `<select>` öğesine değer yazmak
adımı yeşil yapıyor ama ekranda alan hâlâ "Seçiniz" gösteriyor ve uygulamanın o
seçime tepki olarak yüklemesi gereken hiçbir şey yüklenmiyor — bağımlı alanlar
boş kalıyor. Çözüm: bileşeni kullanıcı gibi sürmek, açıp seçeneğe tıklamak.

**Görünmez radyo düğmeleri.** Çizilen daire aslında `<label>`; asıl input
görsel olarak gizli ve Playwright gizli öğeye tıklamayı reddediyor. Çözüm:
kullanıcının tıkladığı şeye, etikete tıklamak.

**Ajan döngüsünün doğal sonu yok.** Modele JavaScript çalıştırma aracı
verildiğinde tıklamayı bırakıp kod yazmaya başladı ve kendi sözdizimi
hatalarında döngüye girdi. Akışı bitirip cevap verdikten sonra baştan başladı.
Her seferinde çözüm aynı biçimi aldı: **prompt kuralı yetmiyor, kod gerekiyor.**
Tehlikeli araçlar modelden esirgendi, ziyaret edilmiş sayfaya ikinci gidiş
reddedildi, katalog bir araç çağrısına dönüştürüldü ve o çağrı döngüyü
gerçekten sonlandıracak şekilde işaretlendi.

**Doğru soruyu doğru yerde sormak.** Kataloğun "bu alan onay ekranında geri
görülebilir mi" bayrağı hiçbir koşuda dolmuyordu. Sebep ajanın dikkatsizliği
değildi: bu soru alan **doldurulurken** soruluyordu, oysa cevabı ancak gönderim
**sonrasında** görülebilecek bir ekranda. Cevabı var olmayan bir soruya hiçbir
ifade yardım etmez. Soru, cevabın bulunduğu ana taşındı — ayrı bir araç olarak.

**Sonra aynı ders bir kez daha:** ayrı araç da atlanabiliyordu, çünkü *isteğe
bağlı* ve akışın ortasındaydı. Bilgi, ajanın **asla unutmadığı** çağrıya
taşındı — kataloğu kapatan çağrıya, çünkü onu yapmazsa ortada katalog olmuyor —
ve orada **zorunlu argüman** haline getirildi. Ayrım ince ama belirleyici:
atlanan bir isteğe bağlı argüman hiçbir iz bırakmaz, oysa zorunlu bir argümana
verilen "boş" cevabı denetleyebilirsin.

**Yanlış doğrulama, eksik doğrulamadan kötüdür.** Bu bayrağı "her alana koyalım
olsun bitsin" demek cazip ama zararlı: onay ekranında olmayan bir alanı
doğrulamaya kalkan senaryo, var olmayan bir öğeyi 30 saniye arar ve **uygulamada
hiçbir sorun yokken kırmızı** olur. İki hatanın bedeli simetrik değil — eksik
bayrak testi zayıflatır, yanlış bayrak sağlıklı bir uygulamayı hatalı gösterir.
Bu yüzden bayrak varsayılmaz, ölçülür.

---

## 8. Bugün ne çalışıyor

- Cucumber + Playwright altyapısı, 20 adımlık kanıtlanmış sözlük
- İki farklı uygulama (NGC kurumsal, NGI bireysel) ortak katmanla
- Uçtan uca gerçek akışlar: Banko ödeme talebi, Online DBS fatura oluşturma —
  girişten başarı mesajına, kuruş hassasiyetiyle
- LLM ile Gherkin üretimi, banka içi modelle
- Katalog farkındalıklı doğrulayıcı
- Analist arayüzü: senaryo düzenleme, doğrulama, çalıştırma, canlı adım
  çizelgesi, geçmiş, rapor ve trace indirme
- Docker yığını: 1 orchestrator + 3 executor, ayakta ve doğrulanmış
- İki katalog üreticisi: deterministik tarama ve MCP ajanı

## 9. Açık maddeler — dürüst liste

- **Kalıcılık yok.** Koşu geçmişi bellekte; yeniden başlatınca gidiyor.
  Veritabanı henüz bağlanmadı. Hangi tarafa bağlanacağı karara bağlandı
  (yalnızca orchestrator), tablo yazılmadı.
- **Ajan hata aldığında yeniden bakmıyor.** Bir tıklama başarısız olduğunda
  model sayfayı yeniden okumak yerine aynı tahmini tekrarlıyor — bir koşuda
  aynı çağrı otuzdan fazla kez, aralarında tek bir okuma olmadan. Mevcut
  korumalar toplam çağrıyı ve tekrar gezinmeyi sayıyor, ama "aynı araç, aynı
  argüman, yine hata" durumunu saymıyor. Bu, ajan tarafındaki bir sonraki
  iş. Not: bu bir ajan sağlamlığı sorunu — üretim hattının kendisi (üretim,
  doğrulama, koşturma) bundan etkilenmiyor.
- **Giriş ekranı ajanı yoruyor.** İki adımlı girişte iki ayrı "Giriş" düğmesi
  DOM'da birlikte duruyor; ajan her koşuda bunu yeniden çözmeye çalışıp
  çağrı harcıyor. Oysa bu ekran test tarafında **zaten çözülmüş** durumda.
  Doğru çözüm girişi ajandan almak, koda vermek.
- Ajanın ürettiği katalog hâlâ insan gözü istiyor; bazı alanları ve onay
  ekranındaki bazı satırları kaçırabiliyor.
- Bir seçim sonucu ekranda beliren alanlar (`shown_when`) hiçbir üretici
  tarafından otomatik bulunamıyor, elle ekleniyor.
- Artefakt saklama politikası yok; trace'ler hızlı büyüyor.

---

## 10. Sayılar

| | |
|---|---|
| adım sözlüğü | 17 kalıp, koddan üretiliyor |
| executor imajı | 4.97 GB (Playwright + tarayıcılar) |
| orchestrator imajı | 444 MB |
| eşzamanlı koşu kapasitesi | 3 (executor başına 1) |
| doğrulama süresi | ~50 ms, tarayıcı açılmadan |
| bir senaryo koşusu | dakikalar |
| desteklenen uygulama | 2 (NGC, NGI) |

---

## 11. Anlatının omurgası — sunumda vurgulanacak üç fikir

1. **Kısıt, özgürlükten değerlidir.** Model kod üretmiyor, yalnızca önceden
   kanıtlanmış fiillerden cümle kuruyor. Güven buradan geliyor.
2. **Sessiz başarı, gürültülü başarısızlıktan kötüdür.** Bu projedeki en pahalı
   hataların hepsi "yeşil ama yanlış" hatalarıydı. Tasarımın büyük kısmı bunu
   imkânsız kılmak için var.
3. **Prompt kuralı yetmez, kod gerekir.** Modelden rica edilen her davranış er
   geç ihlal edildi; ancak yapıya dönüştürülünce durdu.
