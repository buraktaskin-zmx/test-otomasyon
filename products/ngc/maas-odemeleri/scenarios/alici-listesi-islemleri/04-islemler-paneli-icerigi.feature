# @needs:none — "bu senaryo hiçbir veri TÜKETMEZ", açıkça yazılmış.
#
# Etiketi hiç yazmamakla aynı şey değil ve fark bu projenin tekrar tekrar
# öğrendiği şey: boş bir liste iki anlama gelir. "Ön koşulları kimse
# yazmadı" ile "biri baktı ve yok" diskte aynı görünür; yalnızca ikincisi
# bitmiş bir senaryodur. Ürünler ekranı ilkini "belirtilmemiş" diye uyarır.

@R-AL-010 @needs:none
Feature: Alıcı Listesi İşlemleri

  Scenario: İşlemler panelinde üç seçenek bulunur
    Ekran açıldığında, hiçbir satır seçili olmasa bile İşlemler panelinde
    Detay, Sil ve Yeni Liste Oluştur görünür durumdadır.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    Then verify "Detay" text is displayed
    And verify "Sil" text is displayed
    And verify "Yeni Liste Oluştur" text is displayed
