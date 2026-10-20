# İkinci ekranın klasörü. Klasör adı EKRANDIR: alici-listesi-detay, yani
# katalog adı da o olmalı. Uyuşmazlık gizlenmez — Ürünler ekranı kimsenin
# ilan etmediği bir klasörü "bildirilmemiş" olarak listeler.
#
# @needs:LISTE_DOLU -> cozum: seed, engel: S-18. Yani ön koşul "seed"
# görünmez, "eksik" görünür: S-18 cevaplanmadan bu senaryo koşamaz, aliasın
# üretilebilir olması bunu değiştirmez.

@R-KE-001 @needs:MAKER_01 @needs:LISTE_DOLU @mutating
Feature: Alıcı Listesi Detay

  Scenario: 11 karakterden kısa TCKN reddedilir
    Yeni Kayıt Ekle formuna iki haneli bir TCKN girilir; uzunluk hatası
    gösterilir ve kayıt eklenmez.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    And select row "LISTE_DOLU"
    And click "Detay"
    And click "Yeni Kayıt Ekle"
    And enter "TCKN" "22"
    And enter "Hesap No" "60526425"
    And enter amount "Tutar" "3,00"
    And click "Kaydet"
    Then verify "11 karakter uzunluğunda bir değer giriniz." text is displayed
