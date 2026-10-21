Feature: Banko Alacak İskonto Oluşturma
  Bekleyen bir alacak seçilip tüm tutar üzerinden iskonto oluşturma.

  Scenario: Bekleyen alacağa tüm tutar iskontosu uygulanır
    Given login with cif "DI00002018" and userid "1335"

    And navigate to "NAKİT YÖNETİMİ" "Banko İşlemleri"
    And click "Alacak İşlemleri"
    And click "Bekleyen Alacaklar"

    And select row "25/12/2026"
    And click "İskonto Oluştur"

    And click "Devam"

    Then verify the "Ödeme Tutarı" has amount "1.000,00"
    And verify the "Vade Tarihi" field equals "25/12/2026"

    And click "Onay"

    And wait "15" seconds
