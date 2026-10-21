@R-YL-006 @needs:FIRMA_TEST
Feature: Alıcı Listesi İşlemleri

  Scenario: Yeni liste bilgileri onay özetinde görünür

    Ön koşul:
    - İlgili firma verisi bulunabilmeli
    - Döviz cinsi TL seçilebilmeli

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - İşlemler panelinden Yeni Liste Oluştur'a tıkla
    - Firma alanından 07954-752 ile başlayan firmayı seç
    - Döviz Cinsi olarak TL seç
    - Alıcı Listesi Adı alanına dene3 yaz
    - Devam'a bas

    Beklenen:
    - İşlem Bilgileri ekranı görünür
    - Firma Adı / Kodu: SAINT_GOBAIN Test / 07954
    - Firma Şubesi: ATASEHIR (00752)
    - Döviz Cinsi: TL
    - Alıcı Listesi Adı: dene3

    Given login with cif "LH01600254" and userid "1081"

    And navigate to "ÖDEMELER" "Alıcı Listesi İşlemleri"
    And click "Yeni Liste Oluştur"

    And select "07954-752" from "Firma"
    And select "TL" from "Döviz Cinsi"
    And enter "Alıcı Listesi Adı" "dene3"

    And click "Devam"

    Then verify "İşlem Bilgileri" text is displayed
    And verify the "Firma Adı / Kodu" field equals "SAINT_GOBAIN Test / 07954"
    And verify the "Firma Şubesi" field equals "ATASEHIR (00752)"
    And verify the "Döviz Cinsi" field equals "TL"
    And verify the "Alıcı Listesi Adı" field equals "dene3"
