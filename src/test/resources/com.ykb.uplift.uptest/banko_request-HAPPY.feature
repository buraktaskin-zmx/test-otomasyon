Feature: Banko Request Payment Happy Path
  User has limit, auth and Banko settings and sms login.

  Scenario: User can enter information and request payment from Banko interface.
    Given login with cif "DI00002018" and userid "3014"
    And  navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"
    And click "Ödeme Talep Et"

    And enter "TCKN / VKN" "21203030786"
    And enter "Ad Soyad / Unvan" "Test"
    And enter amount "Ödeme Tutarı" "1250,55"
    And enter "Vade Tarihi" "27/08/2026"
    And enter "Açıklama" "Test"
    And select account "0006 7010 0000 0003 4322" from "Alıcı Hesabı"


    And click "Devam"

    Then verify the "TCKN / VKN" field equals "21203030786"
    Then verify the "Ad Soyad / Unvan" field equals "Test"
    Then verify the "Ödeme Tutarı" has amount "1250,55"
    Then verify the "Vade Tarihi" field equals "27/08/2026"
    Then verify the "Açıklama" field equals "Test"

    And click "Onay"
    Then verify "Ödeme talebiniz karşı tarafa iletilmiştir. Tarafınıza yapılacak ödemenin detaylarını Banko İşlemleri'nden görüntüleyebilirsiniz." text is displayed
