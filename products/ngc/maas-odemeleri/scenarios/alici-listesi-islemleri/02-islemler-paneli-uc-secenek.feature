@R-AL-010 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: İşlemler panelinde üç seçenek bulunur

    Adımlar:
    - Alıcı Listesi İşlemleri ekranını aç
    - Hiçbir satır seçme
    - İşlemler panelini incele

    Beklenen:
    - Detay, Sil ve Yeni Liste Oluştur seçenekleri görünür durumdadır
