Feature: DBS Firm Invoice Inquiry
  User has auth and has invoices.

  Scenario: User can see invoices
    Given login with cif "SS27856224" and userid "1244"

    And  navigate to "NAKİT YÖNETİMİ" "Firma Fatura Sorgulama"

    And select "ÇAYKUR ÇAY Online DBS" from "Firma Seçimi"
    And select "Bu Hafta" from "Hızlı Tarih Seçimi"

    And wait "1000" seconds

    Then verify the "Firma Kodu" field equals "CAYKUR"

    Then verify the "Toplam Fatura Tutarı" has amount "54.999.999,50"

    And wait "90" seconds