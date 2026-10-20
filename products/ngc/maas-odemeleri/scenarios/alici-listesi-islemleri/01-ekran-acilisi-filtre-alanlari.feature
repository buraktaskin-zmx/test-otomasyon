@R-AL-014 @R-AL-015 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: Ekran açıldığında filtre alanları görünür

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Ekranda Firma, Döviz Cinsi ve Hızlı Tarih Seçim alanlarının bulunduğunu doğrula
    - Sağ tarafta Detaylı Arama bağlantısının bulunduğunu doğrula

    Beklenen:
    - Üç filtre alanı da görünür durumdadır
    - Hiçbiri seçili değildir
