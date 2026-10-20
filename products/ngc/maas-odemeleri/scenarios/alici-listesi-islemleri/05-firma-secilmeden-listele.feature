@R-AL-011 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: Firma seçilmeden Listele çalışmaz

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Detaylı Arama alanını genişlet
    - Firma alanını boş bırak
    - Döviz Cinsi olarak TL seç
    - Listele butonuna tıkla

    Beklenen:
    - Sorgu çalışmaz
    - Tablo sonuçla dolmaz
