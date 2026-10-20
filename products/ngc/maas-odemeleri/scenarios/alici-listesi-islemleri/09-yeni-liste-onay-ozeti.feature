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

    Given navigate to "ÖDEMELER" "Alıcı Listesi İşlemleri"
    When click "Yeni Liste Oluştur"
    And select "07954-752" from "Firma"
    And select "TL" from "Döviz Cinsi"
    And enter "Alıcı Listesi Adı" "dene3"
    And click "Devam"
    Then verify "İşlem Bilgileri" text is displayed
    And verify "SAINT_GOBAIN Test / 07954" text is displayed
    And verify "ATASEHIR (00752)" text is displayed
    And verify the "Alıcı Listesi Adı" field equals "dene3"
