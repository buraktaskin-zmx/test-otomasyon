# @needs:LISTE_BOS -> cozum: seed. Yani veri YOK ama test onu kendisi
# yaratabilir: "seed" rozeti "koşamaz" demek değil, "önce kendi verisini
# kurar" demektir. @mutating de bu yüzden burada: koşum sonunda temizlenir.

@R-AL-013 @needs:MAKER_01 @needs:LISTE_BOS @mutating
Feature: Alıcı Listesi İşlemleri

  Scenario: Tabloda yalnızca tek satır seçilebilir
    İkinci satır seçilince ilk satırın seçimi kalkar.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    And select "SAINT_GOBAIN Test" from "Firma"
    And select "TL" from "Döviz Cinsi"
    And click "Listele"
    And select row at index 0
    And select row at index 1
    Then verify "Detay" text is displayed
