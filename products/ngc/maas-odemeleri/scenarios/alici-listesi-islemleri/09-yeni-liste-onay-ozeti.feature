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
