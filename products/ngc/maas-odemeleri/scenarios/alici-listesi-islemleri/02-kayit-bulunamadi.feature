@R-AL-009 @needs:MAKER_01 @needs:FIRMA_LISTELI
Feature: Alıcı Listesi İşlemleri

  Scenario: Kayıt bulunamadı durumu
    Gelecekteki bir tarih aralığı sorgulanır; tablo yerine bilgi mesajı
    görünür. Boş sonuç veriyle değil filtreyle üretilir, bu yüzden hiç
    listesi olmayan bir firmaya ihtiyaç yoktur.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    And select "SAINT_GOBAIN Test" from "Firma"
    And select "TL" from "Döviz Cinsi"
    And click "Detaylı Arama"
    And enter date "Başlangıç Tarihi" "+1 yıl"
    And enter date "Bitiş Tarihi" "+1 yıl"
    And click "Listele"
    Then verify "Kayıt bulunamadı." text is displayed
