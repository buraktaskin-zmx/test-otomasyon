Feature: Online DBS Fatura Oluştur
  FORD DBS ODEMELERI kurumu için taksitli bir DBS faturası oluşturma.

  Scenario: Bayi seçilip fatura oluşturulur
    Given login with cif "DI00002018" and userid "1335"

    And navigate to "NAKİT YÖNETİMİ" "Online DBS İşlemleri"
    And select row "FORD DBS ODEMELERI"
    And click "Fatura Oluştur"

    And choose "Bilgi Girişi"

    And select "93002" from "Bayi Kodu"
    Then verify the "Bayi Ünvanı" field equals "TEST / TEST UNVANI"

    And enter "Başlangıç Vadesi" "19/09/2026"
    And enter "Fatura No" "11"
    And enter "Orijinal Fatura Numarası" "1111"

    And select "TL" from "Döviz Cinsi"

    And enter "Taksit Adedi" "3"
    And enter amount "Toplam Tutar" "200,00"
    And enter amount "Orijinal Fatura Tutarı" "66,00"

    And select "Ay" from "Taksit Periyot Birimi"
    And enter "Taksit Periyodu" "2"
    And enter "Fatura Dönemi" "2"
    And enter "Fatura Tarihi" "30/09/2026"

    And select "Normal" from "Fatura Tipi"

    And click "Devam"

    Then verify the "Bayi Kodu" field equals "93002"
    And verify the "Toplam Tutar" has amount "200,00"

    And click "Onay"

    And wait "15" seconds
