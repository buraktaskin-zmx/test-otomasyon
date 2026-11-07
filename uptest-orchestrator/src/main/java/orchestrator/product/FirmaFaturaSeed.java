package orchestrator.product;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GEÇİCİ: Firma Fatura İşlemleri ekranına başlıklarıyla birlikte senaryo
 * listesini bir kez ekler. Senaryolar VDI veritabanına girdikten sonra bu
 * dosya silinir.
 *
 * UI'daki "+" ile aynı yoldan geçer (ProductService.createScenario): Gherkin
 * kontrolü, dosya numarası, başlığı adıyla bulma/oluşturma. Ekranda aynı
 * başlıklı senaryo zaten varsa atlar -- createScenario aynı başlığı ikinci
 * kez numarasını artırıp ekler, bu yüzden kontrol burada.
 *
 * ScenarioImporter gibi bir olay dinleyicisi: ApplicationRunner olsaydı
 * {@code @SpringBootTest} altında da çalışıp yazardı.
 */
@Component
public class FirmaFaturaSeed {

    private static final String APP = "ngc";
    private static final String PRODUCT = "nakit-yonetimi";
    private static final String FLOW = "firma-fatura-islemleri";
    private static final String FEATURE = "Firma Fatura İşlemleri";

    private final ProductService products;

    public FirmaFaturaSeed(ProductService products) {
        this.products = products;
    }

    /** Bir senaryo. Liste maddeleri " | " ile ayrılır; boş metin = bölüm yok. */
    private record S(String heading, String title, String pre, String steps, String expected) {
    }

    private static final String A = "Ekran Açılışı ve Filtre Alanları";
    private static final String B = "Sorgulama ve Doğrulama";
    private static final String C = "Özet Bandı ve Tablo";
    private static final String D = "İşlemler Paneli ve Detay";
    private static final String E = "Fatura Oluştur";
    private static final String F = "Fatura Güncelle ve İptal";

    private static final String AC = "Firma Fatura İşlemleri ekranını aç";
    private static final String APPLE = "Firma Seçimi alanından APPLE A.Ş. seç";
    private static final String SANKO = "Firma Seçimi alanından Sanko DBS Ödemeleri seç";
    private static final String ASAS = "Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç";
    private static final String HIZLI = "Hızlı Arama bağlantısına tıkla";
    private static final String OLUSTUR = "İşlemler panelinden Fatura Oluştur’a tıkla";
    private static final String BILGI = "Oluşturma Tipi olarak Bilgi Girişi seç";
    private static final String ODENECEK = "Ödenecek durumundaki bir faturayı seç";

    private static final List<S> SCENARIOS = List.of(
            // A
            new S(A, "Ekran açıldığında hızlı filtre alanları görünür", "",
                    AC + " | Filtre alanını incele",
                    "Firma Seçimi ve Hızlı Tarih Seçimi alanları görünür durumdadır | Sağ tarafta Hızlı Arama bağlantısı görünür durumdadır | Firma Seçimi alanı Seçiniz değerindedir"),
            new S(A, "Firma seçilmeden tablo boş gelir", "",
                    AC + " | Firma Seçimi alanını boş bırak | Tabloyu incele",
                    "Tablo gövdesinde “Kayıt bulunamadı.” mesajı görünür | Özet bandı görünmez"),
            new S(A, "Detaylı arama alanları açılır", "",
                    AC + " | " + HIZLI,
                    "Fatura Sorgulama Tarih Kriteri, Başlangıç Tarihi, Bitiş Tarihi, Fatura Sorgulama Bayi Kriteri, Bayi Kodu, Statü, Minimum Tutar, Maksimum Tutar, Para Birimi ve Fatura Numarası alanları görünür | Listele butonu görünür"),
            new S(A, "Detaylı arama kapatılabilir", "",
                    AC + " | Hızlı Arama bağlantısına tıkla, alanların açıldığını gör | Aynı bağlantıya tekrar tıkla",
                    "Detaylı arama alanları gizlenir | Filtre alanı ilk haline döner"),
            new S(A, "Filtre alanlarının varsayılan değerleri gelir", "",
                    AC + " | " + HIZLI,
                    "Hızlı Tarih Seçimi: Bu Hafta | Fatura Sorgulama Tarih Kriteri: Son Ödeme Tarihi | Fatura Sorgulama Bayi Kriteri: Bayi Kodu | Bayi Kodu: Hepsi | Statü: Hepsi | Para Birimi: Seçiniz"),
            new S(A, "Firma Seçimi listesinde arama yapılır",
                    "Sanko DBS Ödemeleri firması listede bulunabilmeli",
                    AC + " | Firma Seçimi alanına tıkla | Arama kutusuna san yaz",
                    "Listede yalnızca aranan metni içeren firmalar kalır | Sanko DBS Ödemeleri seçeneği listede görünür"),
            new S(A, "Hızlı tarih seçimi tarih alanlarını doldurur", "",
                    AC + " | " + HIZLI + " | Hızlı Tarih Seçimi alanından Bu Hafta seç",
                    "Başlangıç Tarihi ve Bitiş Tarihi alanları seçilen aralığa göre dolar | Her iki alanın yanında takvim simgesi bulunur"),
            new S(A, "Tarih kriteri değiştirilebilir", "",
                    AC + " | " + HIZLI + " | Fatura Sorgulama Tarih Kriteri alanına tıkla",
                    "Alan açılır ve seçenekler listelenir | Seçilen değer alanda görünür"),
            // B
            new S(B, "Minimum tutar maksimumdan büyük olamaz",
                    "APPLE A.Ş. firması seçilebilmeli",
                    AC + " | " + APPLE + " | " + HIZLI + " | Minimum Tutar alanına 1000 yaz | Maksimum Tutar alanına 100 yaz | Listele butonuna tıkla",
                    "“Minimum tutar, maksimum tutardan büyük olamaz.” uyarısı görünür | Sorgu çalışmaz"),
            new S(B, "Başlangıç tarihi bitiş tarihinden büyük olamaz",
                    "APPLE A.Ş. firması seçilebilmeli",
                    AC + " | " + APPLE + " | " + HIZLI + " | Başlangıç Tarihi alanına 26/10/2030 gir | Bitiş Tarihi alanına 01/11/2026 gir | Listele butonuna tıkla",
                    "“Bitiş tarihinden küçük bir tarih olmalıdır.” uyarısı görünür | “Başlangıç tarihinden büyük bir tarih olmalıdır.” uyarısı görünür | Sorgu çalışmaz"),
            new S(B, "Firma seçilince sorgu otomatik çalışır",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE,
                    "Özet bandı görünür hale gelir | Tabloda o firmaya ait faturalar listelenir"),
            new S(B, "Listele butonu sorguyu yeniler",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | " + HIZLI + " | Listele butonuna tıkla",
                    "Sorgu çalışır | Tabloda seçilen kriterlere uyan faturalar listelenir"),
            new S(B, "Fatura numarası ile sorgulanır",
                    "APPLE A.Ş. firmasında anilbelge2 numaralı fatura bulunabilmeli",
                    AC + " | " + APPLE + " | " + HIZLI + " | Fatura Numarası alanına anilbelge2 yaz | Listele butonuna tıkla",
                    "Tabloda yalnızca anilbelge2 numaralı fatura listelenir"),
            new S(B, "Tutar aralığı filtresi çalışır",
                    "APPLE A.Ş. firmasında farklı tutarlarda fatura bulunabilmeli",
                    AC + " | " + APPLE + " | " + HIZLI + " | Minimum Tutar alanına 100 yaz | Maksimum Tutar alanına 200 yaz | Listele butonuna tıkla",
                    "Tabloda yalnızca fatura tutarı 100 TL ile 200 TL arasındaki kayıtlar görünür"),
            new S(B, "Para birimi filtresi çalışır",
                    "ASAŞ ALÜMİNYUM SAN. firmasında farklı para birimlerinde fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + HIZLI + " | Para Birimi alanından TL seç | Listele butonuna tıkla",
                    "Tabloda yalnızca TL para birimindeki faturalar görünür"),
            new S(B, "Statü filtresi liste sonucunu daraltır",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + HIZLI + " | Statü alanından Ödenecek seç | Listele butonuna tıkla",
                    "Tabloda yalnızca Ödeme Durumu Ödenecek olan kayıtlar görünür"),
            new S(B, "Bayi kodu filtresi çalışır",
                    "APPLE A.Ş. firmasında 151732 bayi koduna ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | " + HIZLI + " | Bayi Kodu alanından 151732 seç | Listele butonuna tıkla",
                    "Tabloda yalnızca Bayi Kodu 151732 olan kayıtlar görünür"),
            new S(B, "Sonuç bulunmayan sorguda bilgi mesajı çıkar",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + HIZLI + " | Fatura Numarası alanına 00000000 yaz | Listele butonuna tıkla",
                    "Tablo gövdesinde “Kayıt bulunamadı.” mesajı görünür | İşlemler panelinde satır bazlı seçenekler görünmez"),
            new S(B, "Firma değişince önceki sonuçlar temizlenir",
                    "Her iki firma da seçilebilmeli",
                    AC + " | Firma Seçimi alanından APPLE A.Ş. seç, faturaların listelendiğini gör | " + SANKO,
                    "Tablo yeni firmaya göre yenilenir | Özet bandı yeni firmanın değerlerini gösterir | Önceki firmanın kayıtları görünmez"),
            // C
            new S(C, "Özet bandı firma bilgilerini gösterir",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE,
                    "Firma Kodu alanı görünür ve APPYAKOPS değerindedir | Toplam Fatura Tutarı, Onaylı Tutar (TL) ve Toplam Kalan Bakiye alanları görünür"),
            new S(C, "Çok para birimli özet ayrı satırlarda görünür",
                    "ASAŞ ALÜMİNYUM SAN. firmasında TL, USD ve EUR faturaları bulunabilmeli",
                    AC + " | " + ASAS + " | Özet bandını incele",
                    "Toplam Fatura Tutarı alanında her para birimi ayrı satırda görünür | Aynı ayrım Onaylı Tutar ve Toplam Kalan Bakiye alanlarında da görünür"),
            new S(C, "Tablo kolonları görünür",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | Tablo başlığını incele",
                    "Bayi Kodu, Fatura No, Son Ödeme Tarihi, Fatura Tutarı, Kalan Tutar ve Ödeme Durumu kolonları görünür | Her kolon başlığında sıralama simgesi bulunur"),
            new S(C, "Kolon başlığına tıklanınca sıralama yapılır",
                    "APPLE A.Ş. firmasında en az iki fatura bulunabilmeli",
                    AC + " | " + APPLE + " | Fatura Tutarı kolon başlığındaki sıralama simgesine tıkla",
                    "Kayıtlar fatura tutarına göre artan sırada listelenir | Aynı başlığa tekrar tıklanınca azalan sırada listelenir"),
            new S(C, "Tablo içinde bul alanı kayıt süzer",
                    "APPLE A.Ş. firmasında anilbelge2 numaralı fatura bulunabilmeli",
                    AC + " | " + APPLE + " | Tablo içinde bul alanına anilbelge2 yaz | Arama simgesine tıkla",
                    "Tabloda yalnızca aranan değeri içeren satır kalır"),
            new S(C, "Negatif tutarlar ayırt edilebilir görünür",
                    "ASAŞ ALÜMİNYUM SAN. firmasında negatif tutarlı fatura bulunabilmeli",
                    AC + " | " + ASAS + " | Tabloyu incele",
                    "Negatif tutarlı satırların Fatura Tutarı ve Kalan Tutar değerleri eksi işaretiyle görünür"),
            new S(C, "Dışa aktarma bağlantıları bulunur",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | Tablonun altını incele",
                    "Pdf, Excel ve Yazdır bağlantıları görünür durumdadır"),
            // D
            new S(D, "Satır seçilmeden İşlemler panelinde üç seçenek bulunur",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | Hiçbir satır seçme | İşlemler panelini incele",
                    "Fatura Oluştur, Limit Risk Sorgulama ve Dosya İzleme seçenekleri görünür | Detay, Fatura Güncelle ve Fatura İptal Et seçenekleri görünmez"),
            new S(D, "Satır seçilince üç seçenek daha görünür",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | Ödenecek durumundaki bir satırın seçim kutusunu işaretle",
                    "İşlemler panelinde Detay, Fatura Güncelle ve Fatura İptal Et seçenekleri görünür hale gelir | Seçilen satır vurgulanır"),
            new S(D, "Aynı anda tek satır seçilebilir",
                    "APPLE A.Ş. firmasında en az iki fatura bulunabilmeli",
                    AC + " | " + APPLE + " | İlk satırın seçim kutusunu işaretle | İkinci satırın seçim kutusunu işaretle",
                    "Yalnızca ikinci satır seçili kalır | İlk satırın seçimi kaldırılır"),
            new S(D, "Fatura detayında bayi kodu doğrulanır",
                    "APPLE A.Ş. firmasında 151732 bayi koduna ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | 151732 bayi kodlu faturayı seç | İşlemler panelinden Detay’a tıkla",
                    "Fatura Bilgileri penceresi açılır | Bayi Kodu alanı 151732 değerindedir"),
            new S(D, "Fatura detayı tüm alanları gösterir",
                    "APPLE A.Ş. firmasında anilbelge2 numaralı fatura bulunabilmeli",
                    AC + " | " + APPLE + " | anilbelge2 numaralı faturayı seç | İşlemler panelinden Detay’a tıkla",
                    "Fatura Bilgileri penceresi açılır | Ana Firma Adı: APPLE A.Ş. | Bayi Kodu: 151732 | Fatura No: anilbelge2 | Fatura Tipi: E-Fatura | Fatura Tutarı: 111,20 TL | Durum: Ödenecek | Vade Tarihi, Fatura Tarihi, Oluşturulma Tarihi ve Ek Bilgi alanları görünür"),
            new S(D, "E-Fatura tipinde hash kodu görünür",
                    "Fatura Tipi E-Fatura olan bir kayıt bulunabilmeli",
                    AC + " | " + APPLE + " | Fatura Tipi E-Fatura olan bir faturayı seç | İşlemler panelinden Detay’a tıkla",
                    "Fatura Bilgileri penceresinde E-Fatura Hash Kodu alanı görünür ve dolu gelir"),
            new S(D, "Detaydaki değerler tablo satırıyla uyuşur",
                    "APPLE A.Ş. firmasında anilbelge2 numaralı fatura bulunabilmeli",
                    AC + " | " + APPLE + " | anilbelge2 satırındaki Fatura Tutarı ve Ödeme Durumu değerlerini not et | Aynı satırı seç ve Detay’a tıkla",
                    "Penceredeki Fatura Tutarı tablodaki değerle aynıdır | Penceredeki Durum tablodaki Ödeme Durumu ile aynıdır"),
            new S(D, "Fatura detayı kapatılabilir",
                    "APPLE A.Ş. firmasına ait fatura bulunabilmeli",
                    AC + " | " + APPLE + " | Bir faturayı seç | İşlemler panelinden Detay’a tıkla | Kapat butonuna tıkla",
                    "Fatura Bilgileri penceresi kapanır | Liste ekranı korunur, satır seçimi değişmez"),
            // E
            new S(E, "Yetkisiz firmada fatura oluşturulamaz",
                    "APPLE A.Ş. firmasında fatura oluşturma yetkisi bulunmamalı",
                    AC + " | " + APPLE + " | " + OLUSTUR,
                    "Fatura Oluşturma ekranı açılmaz | Yetki uyarısı görünür"),
            new S(E, "Yetkili firmada oluşturma tipi ekranı açılır",
                    "Sanko DBS Ödemeleri firmasında fatura oluşturma yetkisi bulunmalı",
                    AC + " | " + SANKO + " | " + OLUSTUR,
                    "Fatura Oluşturma ekranı açılır | Fatura Bilgi Giriş ve Onay adımlarını gösteren ilerleme çubuğu görünür | Oluşturma Tipi alanında Bilgi Girişi ve Dosya Yükleme seçenekleri görünür | İkisi de seçili değildir"),
            new S(E, "Oluşturma tipi seçilmeden form açılmaz",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | Hiçbir oluşturma tipi seçme",
                    "Fatura bilgi giriş formu görünmez | Ekranda yalnızca Geri butonu bulunur"),
            new S(E, "Bilgi girişi formu alanları görünür",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI,
                    "Kurum alanı Sanko DBS Ödemeleri değeriyle salt okunur görünür | Bayi Kodu, Bayi Ünvanı, Başlangıç Vadesi, Fatura No, Orijinal Fatura Numarası, Döviz Cinsi, Taksit Adedi, Toplam Tutar, Orijinal Fatura Tutarı, Taksit Periyot Birimi, Taksit Periyodu, Fatura Dönemi, Fatura Tarihi ve Fatura Tipi alanları görünür | Ek Bilgi Girişi bölümü ve Geri / Devam butonları görünür"),
            new S(E, "Döviz cinsi yalnızca TL seçilebilir",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Döviz Cinsi alanına tıkla",
                    "Listede yalnızca Seçiniz ve TL seçenekleri bulunur | Başka bir para birimi seçeneği görünmez"),
            new S(E, "Bayi kodu listesi açılır",
                    "Sanko DBS Ödemeleri firmasına tanımlı bayi bulunabilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Bayi Kodu alanına tıkla",
                    "Firmaya tanımlı bayi kodları listelenir | Liste içinde arama kutusu bulunur"),
            new S(E, "Taksit periyot birimi seçenekleri listelenir",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Taksit Periyot Birimi alanına tıkla",
                    "Seçiniz, Ay, Hafta ve Gün seçenekleri listelenir"),
            new S(E, "Fatura tipi seçenekleri listelenir",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Fatura Tipi alanına tıkla",
                    "Seçiniz, Normal, E-Fatura ve E-Arşiv Fatura seçenekleri listelenir"),
            new S(E, "Tutar alanları lira ve kuruş olarak ayrıdır",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Toplam Tutar alanını incele",
                    "Toplam Tutar alanı lira ve kuruş olmak üzere iki ayrı kutudan oluşur | Varsayılan değerler 0 ve 00 olarak gelir | Aynı yapı Orijinal Fatura Tutarı alanında da bulunur"),
            new S(E, "Zorunlu alanlar boş bırakılınca devam edilemez",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Tüm alanları boş bırak | Devam butonuna tıkla",
                    "Zorunlu alanlar için uyarı görünür | Ekran Onay adımına geçmez"),
            new S(E, "Ek bilgi satırı eklenebilir",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Ek Bilgi Girişi bölümünde Yeni Satır butonuna tıkla",
                    "Tabloya yeni bir ek bilgi satırı eklenir | Her satırın sağında silme simgesi bulunur"),
            new S(E, "Ek bilgi satırı silinebilir",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Yeni Satır butonuna tıkla | Eklenen satırın silme simgesine tıkla",
                    "Eklenen satır tablodan kaldırılır"),
            new S(E, "Bilgi girişi ile fatura oluşturulur",
                    "Sanko DBS Ödemeleri firmasında fatura oluşturma yetkisi bulunmalı | 151732 bayi kodu seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI
                            + " | Bayi Kodu olarak 151732 seç | Başlangıç Vadesi alanına 27/10/2026 gir | Fatura No alanına 11113 yaz"
                            + " | Orijinal Fatura Numarası alanına 2222 yaz | Döviz Cinsi olarak TL seç | Taksit Adedi alanına 12 yaz"
                            + " | Toplam Tutar alanına 1000,50 yaz | Orijinal Fatura Tutarı alanına 33 yaz | Taksit Periyot Birimi olarak Ay seç"
                            + " | Taksit Periyodu alanına 3 yaz | Fatura Dönemi alanına 3 yaz | Fatura Tarihi alanına 28/10/2026 gir"
                            + " | Fatura Tipi olarak Normal seç | Devam butonuna tıkla",
                    "Onay adımı görünür | Taksit Periyodu: 3 | Fatura Dönemi: 3 | Fatura Tarihi: 28/10/2026 | Fatura Tipi: Normal"),
            new S(E, "Oluşturma ekranından Geri ile listeye dönülür",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | Geri butonuna tıkla",
                    "Firma Fatura İşlemleri liste ekranına dönülür | Yeni fatura kaydı oluşmaz"),
            // F
            new S(F, "Güncelleme ekranı mevcut bilgileri salt okunur gösterir",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + ODENECEK + " | İşlemler panelinden Fatura Güncelle’ye tıkla",
                    "Bilgi Girişi ve Onay adımlarını gösteren ilerleme çubuğu görünür | Kurum Adı, Bayi Kodu, Bayi Ünvanı, Fatura No, Para Birimi, Fatura Tutarı, Son Ödeme Tarihi ve Fatura Dönemi alanları salt okunur görünür"),
            new S(F, "Güncelleme formunda yeni değer alanları bulunur",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + ODENECEK + " | İşlemler panelinden Fatura Güncelle’ye tıkla",
                    "Yeni Fatura No, Yeni Orijinal Fatura No, Yeni Para Birimi, Yeni Fatura Tutarı, Yeni Orijinal Fatura Tutarı, Yeni Son Ödeme Tarihi, Yeni Fatura Dönemi, Yeni Fatura Tarihi ve Yeni Fatura Tipi alanları görünür | Yeni Fatura No ve Yeni Fatura Tutarı alanları mevcut değerlerle dolu gelir"),
            new S(F, "Güncelleme ekranından Geri ile listeye dönülür",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + ODENECEK + " | İşlemler panelinden Fatura Güncelle’ye tıkla | Geri butonuna tıkla",
                    "Firma Fatura İşlemleri liste ekranına dönülür | Faturanın bilgileri değişmez"),
            new S(F, "İptal akışında fatura bilgileri görünür",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + ODENECEK + " | İşlemler panelinden Fatura İptal Et’e tıkla",
                    "İptal onay ekranı açılır | Seçilen faturanın bilgileri görünür | Geri butonu bulunur"),
            new S(F, "İptal ekranından Geri ile listeye dönülür",
                    "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli",
                    AC + " | " + ASAS + " | " + ODENECEK + " | İşlemler panelinden Fatura İptal Et’e tıkla | Geri butonuna tıkla",
                    "Liste ekranına dönülür | Faturanın Ödeme Durumu Ödenecek olarak kalır"),
            // Claude'un eklediği senaryolar: dbs-fatura-olustur kataloğunda ve
            // önceki koşularda gözlenen davranışlardan.
            new S(E, "Bayi kodu seçilince bayi ünvanı kendiliğinden dolar",
                    "Sanko DBS Ödemeleri firmasına tanımlı bayi bulunabilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Bayi Kodu olarak 151732 seç",
                    "Bayi Ünvanı alanı seçilen bayinin ünvanıyla dolar | Bayi Ünvanı alanı salt okunurdur, elle değiştirilemez"),
            new S(E, "Bayi kodu seçilmeden döviz cinsi seçenek sunmaz",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Bayi Kodu alanını boş bırak | Döviz Cinsi alanına tıkla",
                    "Döviz Cinsi listesinde seçilebilir bir para birimi bulunmaz | Bayi Kodu seçildikten sonra TL seçeneği listeye gelir"),
            new S(E, "Başlangıç vadesi bugün olamaz",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Başlangıç Vadesi alanının takvimini aç",
                    "Takvimde bugünün tarihi seçilemez durumdadır | Yarından sonraki günler seçilebilir"),
            new S(E, "Tutar girilmeden devam edilemez",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI
                            + " | Toplam Tutar dışındaki zorunlu alanları doldur | Toplam Tutar alanını 0 ve 00 olarak bırak | Devam butonuna tıkla",
                    "“Toplam tutar giriniz” uyarısı görünür | Ekran Onay adımına geçmez"),
            new S(E, "Tutar tek kutuya biçimli yazılınca kabul edilmez",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI
                            + " | Toplam Tutar lira kutusuna 1.250,50 yaz | Diğer zorunlu alanları doldur | Devam butonuna tıkla",
                    "Toplam tutar lira ve kuruş kutularından okunur; biçimli metin tutar olarak alınmaz | Ekran Onay adımına geçmez ya da onayda yazılandan farklı bir tutar görünür"),
            new S(E, "E-Fatura tipi seçilince hash kodu alanı açılır",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI + " | Fatura Tipi olarak E-Fatura seç",
                    "E-Fatura Hash Kodu alanı formda görünür hale gelir | Fatura Tipi Normal seçildiğinde bu alan görünmez"),
            new S(E, "Dosya yükleme tipi ayrı bir ekran açar",
                    "Sanko DBS Ödemeleri firması seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | Oluşturma Tipi olarak Dosya Yükleme seç",
                    "Bilgi girişi formunun alanları görünmez | Dosya seçmek için bir Gözat butonu görünür"),
            new S(E, "Onay adımından geri dönülünce girilen değerler korunur",
                    "Sanko DBS Ödemeleri firmasında fatura oluşturma yetkisi bulunmalı | 151732 bayi kodu seçilebilmeli",
                    AC + " | " + SANKO + " | " + OLUSTUR + " | " + BILGI
                            + " | Formu geçerli değerlerle doldur | Devam butonuna tıkla | Onay adımında Geri butonuna tıkla",
                    "Bilgi giriş formuna dönülür | Bayi Kodu, Fatura No ve Toplam Tutar alanları girilen değerleri korur")
    );

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            renameOld();
            Set<String> existing = new HashSet<>();
            for (ProductService.Registered entry : products.registered(APP, PRODUCT)) {
                if (FLOW.equals(entry.stored().flow())) {
                    existing.add(key(entry.fields().title()));
                }
            }
            int added = 0;
            int skipped = 0;
            int failed = 0;
            for (S s : SCENARIOS) {
                if (existing.contains(key(s.title()))) {
                    skipped++;
                    continue;
                }
                ProductService.Created created =
                        products.createScenario(APP, PRODUCT, FLOW, feature(s), s.heading());
                if (created.refused() != null) {
                    failed++;
                    System.err.println("[seed] HATA: " + s.title() + " -- " + created.refused());
                } else {
                    added++;
                    existing.add(key(s.title()));
                    System.err.println("[seed] EKLENDİ: " + created.key());
                }
            }
            System.err.println("[seed] Firma Fatura İşlemleri: " + added + " eklendi, "
                    + skipped + " zaten vardı, " + failed + " hata");
            placeLoose();
        } catch (RuntimeException e) {
            // Geçici bir sınıf uygulamanın açılmasını engellememeli.
            System.err.println("[seed] çalışmadı: " + e);
        }
    }

    /**
     * Elle yazılmış dört eski senaryoya listedeki düzgün adını verir. Ekleme
     * adımından ÖNCE çalışır: yeniden adlanan senaryo listede "zaten var"
     * sayılır ve aynı senaryonun ikinci bir kopyası oluşmaz. Adımları ve
     * dosya adı olduğu gibi kalır, yalnızca başlık (ve Gherkin'deki Scenario
     * satırı) değişir.
     */
    private void renameOld() {
        Set<String> seedTitles = new HashSet<>();
        SCENARIOS.forEach(s -> seedTitles.add(key(s.title())));
        Set<String> onScreen = new HashSet<>();
        List<ScenarioStore.Stored> candidates = new ArrayList<>();
        for (ProductService.Registered entry : products.registered(APP, PRODUCT)) {
            if (!FLOW.equals(entry.stored().flow())) {
                continue;
            }
            String title = key(entry.fields().title());
            onScreen.add(title);
            if (!seedTitles.contains(title)) {
                candidates.add(entry.stored());
            }
        }
        for (ScenarioStore.Stored stored : candidates) {
            String old = stored.fields().title();
            String target = newTitleFor(fold(old));
            if (target == null) {
                continue;
            }
            if (onScreen.contains(key(target))) {
                System.err.println("[seed] YENİDEN ADLANDIRILMADI: " + old + " -- ekranda zaten \""
                        + target + "\" var; eskisini UI'dan silebilirsin");
                continue;
            }
            ProductService.SaveResult saved = products.saveScenario(APP, PRODUCT, stored.path(),
                    new ProductService.ScenarioEdit(null, null, target, null, null, null, null,
                            stored.version(), null, null));
            if (saved.refused() != null) {
                System.err.println("[seed] HATA: " + old + " -- " + saved.refused());
            } else {
                onScreen.add(key(target));
                System.err.println("[seed] YENİDEN ADLANDIRILDI: " + old + " -> " + target);
            }
        }
    }

    private static String newTitleFor(String t) {
        if (t.contains("bayi kodu")) {
            return "Fatura detayında bayi kodu doğrulanır";
        }
        if (t.contains("fatura olus")) {
            return "Bilgi girişi ile fatura oluşturulur";
        }
        if (t.contains("min") || t.contains("maks")) {
            return "Minimum tutar maksimumdan büyük olamaz";
        }
        if (t.contains("tarih")) {
            return "Başlangıç tarihi bitiş tarihinden büyük olamaz";
        }
        return null;
    }

    /**
     * Ekranda başlığı olmayan (elle yazılmış, eski) senaryoları başlığına
     * göre bir başlık altına koyar. Zaten bir başlıkta olana dokunmaz;
     * eşleşmeyeni başlıksız bırakıp loga yazar.
     */
    private void placeLoose() {
        for (ProductService.Registered entry : products.registered(APP, PRODUCT)) {
            if (!FLOW.equals(entry.stored().flow()) || entry.stored().headingId() != null) {
                continue;
            }
            String title = entry.fields().title();
            // Önce listedeki aynı başlıklı senaryonun başlığı: ekranda listeyle
            // aynı adı taşıyan eski bir senaryo "zaten var" diye atlanmıştı.
            String heading = SCENARIOS.stream()
                    .filter(s -> key(s.title()).equals(key(title)))
                    .map(S::heading)
                    .findFirst()
                    .orElseGet(() -> headingFor(fold(title)));
            if (heading == null) {
                System.err.println("[seed] BAŞLIKSIZ KALDI: " + title);
                continue;
            }
            ProductService.HeadingResult made = products.addHeading(APP, PRODUCT, FLOW, heading);
            if (made.refused() != null) {
                System.err.println("[seed] HATA: " + title + " -- " + made.refused());
                continue;
            }
            ProductService.Created moved = products.assignHeading(APP, PRODUCT, entry.stored().path(),
                    made.added().heading().id().toString());
            System.err.println(moved.refused() != null
                    ? "[seed] HATA: " + title + " -- " + moved.refused()
                    : "[seed] BAŞLIĞA KONDU: " + title + " -> " + heading);
        }
    }

    /** Eski senaryo adları: bayi kodu doğrulama, fatura oluştur, min maks tutar, tarih. */
    private static String headingFor(String t) {
        if (t.contains("bayi kodu")) {
            return D;
        }
        if (t.contains("fatura olus")) {
            return E;
        }
        if (t.contains("guncelle") || t.contains("iptal")) {
            return F;
        }
        if (t.contains("min") || t.contains("maks") || t.contains("tutar") || t.contains("tarih")) {
            return B;
        }
        return null;
    }

    private static String fold(String text) {
        return key(text).replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
    }

    private static String feature(S s) {
        List<String> lines = new ArrayList<>();
        lines.add("Feature: " + FEATURE);
        lines.add("");
        lines.add("  Scenario: " + s.title());
        section(lines, "Ön koşul:", s.pre());
        section(lines, "Adımlar:", s.steps());
        section(lines, "Beklenen:", s.expected());
        return String.join("\n", lines) + "\n";
    }

    private static void section(List<String> lines, String header, String items) {
        if (items == null || items.isBlank()) {
            return;
        }
        lines.add("");
        lines.add("    " + header);
        for (String item : items.split(" \\| ")) {
            lines.add("    - " + item.trim());
        }
    }

    private static String key(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.forLanguageTag("tr"));
    }
}
