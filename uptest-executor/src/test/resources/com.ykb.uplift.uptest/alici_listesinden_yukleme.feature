Feature: Alıcı Listesi'nden Yükleme
  User has a recipient list with records ready to be uploaded.

  Scenario: User can upload a recipient list group and confirm it
    Given login with cif "LH1600254" and userid "1779"
    And navigate to "ÖDEMELER" "Alıcı Listesi'nden Yükleme"

    And select "1600254BB-752-PINAR MİCRO2" from "Firma"
    And select "Türk Lirası" from "Döviz Cinsi"
    And select "Geçen Ay (Temmuz)" from "Hızlı Tarih Seçim"

    And select row "7491"
    And click "Devam"

    Then verify the "Firma Adı / Kodu" field equals "PINAR MİCRO2 / 1600254BB"
    And verify the "Firma Şubesi" field equals "ATASEHIR (00752)"
    And verify the "Alıcı Listesi Adı" field equals "eliftest2"
    And verify the "Grup No" field equals "7491"
    And verify the "Toplam Kayıt Sayısı" field equals "1"
    And verify the "Toplam Tutar" has amount "600,00"

    And click "Onay"
    Then verify "İşlem başarılı" text is displayed
