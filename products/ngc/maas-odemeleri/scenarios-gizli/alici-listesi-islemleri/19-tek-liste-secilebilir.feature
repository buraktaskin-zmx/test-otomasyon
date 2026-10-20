# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-013 @needs:IKI_LISTE
Feature: Alici Listesi Islemleri

  Scenario: Ayni anda tek liste secilebilir

    En az iki liste donen bir sorgu calistir. Ilk satiri sec, secildigini dogrula.
    Ikinci satiri sec.

    Beklenen: Ikinci satir secili hale gelir, ilk satirin secimi kalkar. Ayni anda yalnizca
    bir satir secilidir.
