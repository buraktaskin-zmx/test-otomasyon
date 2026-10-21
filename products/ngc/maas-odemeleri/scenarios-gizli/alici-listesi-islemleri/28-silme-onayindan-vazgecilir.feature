@R-LS-005 @needs:LISTE_BOS
Feature: Alici Listesi Islemleri

  Scenario: Silme onayindan Geri ile vazgecilir

    AUTO_ onekli bir test listesi sec ve Sil secenegine tikla. Onay ekraninda Geri
    dugmesine bas. Sorguyu tekrar calistir.

    Beklenen: Liste silinmez, sorgu sonucunda yerinde durur.

    Not: Bu senaryo Sil akisina girer ama veriyi DEGISTIRMEZ.
