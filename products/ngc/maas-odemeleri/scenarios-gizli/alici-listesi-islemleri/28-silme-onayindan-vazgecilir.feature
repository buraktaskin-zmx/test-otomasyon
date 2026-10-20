# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-LS-005 @needs:LISTE_BOS
Feature: Alici Listesi Islemleri

  Scenario: Silme onayindan Geri ile vazgecilir

    AUTO_ onekli bir test listesi sec ve Sil secenegine tikla. Onay ekraninda Geri
    dugmesine bas. Sorguyu tekrar calistir.

    Beklenen: Liste silinmez, sorgu sonucunda yerinde durur.

    Not: Bu senaryo Sil akisina girer ama veriyi DEGISTIRMEZ.
