# Bu dosyada @needs etiketi HİÇ YOK — ne bir alias ne de "none".
#
# Ürünler ekranı bunu "belirtilmemiş" diye gösterir, "ön koşulsuz" diye
# değil. Çünkü bilinmiyor: yazarı ya henüz düşünmedi ya da yazmayı unuttu.
# Yeşil göstermek, koşamayacak bir senaryoyu kuyruğa sokmak olurdu.

@R-AL-010
Feature: Alıcı Listesi İşlemleri

  Scenario: Tablo Liste Adına göre sıralanır
    Başlığa tıklanır; satırlar alfabetik artan sırada dizilir.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    And click "Alıcı Listesi Adı"
    Then verify "Detay" text is displayed
