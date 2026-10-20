@R-AL-002 @R-AL-003 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: Firma ve döviz seçilince sorgu çalışır

    Ön koşul:
    - İlgili firma verisi bulunabilmeli
    - Döviz cinsi TL seçilebilmeli

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Firma alanından test firmasını seç
    - Döviz Cinsi olarak TL seç
    - Listele butonuna tıkla

    Beklenen:
    - Sorgu çalışır
    - Tabloda o firmaya ait alıcı listeleri görünür ya da kayıt yoksa bilgi mesajı görünür
