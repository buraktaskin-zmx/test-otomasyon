@R-AL-015 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: Detaylı Arama tarih alanlarını açar

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Detaylı Arama bağlantısına tıkla

    Beklenen:
    - Başlangıç Tarihi ve Bitiş Tarihi alanları ile Listele butonu görünür hale gelir
    - Her iki tarih alanının yanında takvim simgesi bulunur
