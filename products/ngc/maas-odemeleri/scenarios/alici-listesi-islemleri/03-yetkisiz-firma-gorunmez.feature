# Bu dosya KASITLI olarak iki kusur taşıyor; Ürünler ekranının ikisini de
# göstermesi gerekiyor:
#
#   @R-AL-016  -> rules.yaml'da böyle bir kural YOK. Kural çipi "bilinmiyor"
#                 diye işaretlenir. Etiketi sessizce atmak, senaryoyu bağlı
#                 gibi gösterirdi.
#   @needs:VIEWER_01 -> test-data.yaml'da cozum: eksik. Ön koşul "eksik"
#                 görünür ve satır koşturulamaz olarak okunur.

@R-AL-016 @needs:VIEWER_01
Feature: Alıcı Listesi İşlemleri

  Scenario: Yetkili olunmayan firma listede görünmez
    Kullanıcının yetkisi olmayan firma arandığında sonuç dönmez.

    Given login with cif "VIEWER_01" and userid "VIEWER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    And select "SAINT_GOBAIN Test" from "Firma"
    Then verify "Kayıt bulunamadı." text is displayed
