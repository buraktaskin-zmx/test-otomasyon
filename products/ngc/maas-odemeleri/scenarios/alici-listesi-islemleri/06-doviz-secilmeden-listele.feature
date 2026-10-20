@R-AL-011 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: Döviz cinsi seçilmeden Listele çalışmaz

    Ön koşul:
    - İlgili firma verisi bulunabilmeli

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Firma alanından test firmasını seç
    - Döviz Cinsi alanını boş bırak
    - Listele butonuna tıkla

    Beklenen:
    - Sorgu çalışmaz
    - Tablo sonuçla dolmaz
