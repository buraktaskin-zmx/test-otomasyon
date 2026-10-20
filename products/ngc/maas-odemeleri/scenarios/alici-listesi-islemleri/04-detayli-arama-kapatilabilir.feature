@R-AL-015 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: Detaylı Arama kapatılabilir

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Detaylı Arama bağlantısına tıkla, alanların açıldığını gör
    - Aynı bağlantıya tekrar tıkla

    Beklenen:
    - Tarih alanları gizlenir
    - Filtre alanı ilk haline döner
