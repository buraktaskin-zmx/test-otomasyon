Feature: Banko Request Payment Happy Path
  User has limit, auth and Banko settings and sms login.

  Scenario: User can enter information and request payment from Banko interface.
    Given login with cif "DI00002018" and userid "3014"
    And  navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"
    And click "Ödeme Talep Et"
    And enter "TCKN / VKN" "212030307863"
    And enter "Ad Soyad / Unvan" "Test"
    And enter amount "Ödeme Tutarı" "10"
    And enter "Vade Tarihi" "27/08/2026"
    And enter "Açıklama" "Test"
    And click "Devam"
    And wait