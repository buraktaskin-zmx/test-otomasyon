# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-014 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Firma alani yazarak filtrelenir

    Firma alanina tikla. Test firmasinin adinin bir bolumunu yaz.

    Beklenen: Acilan listede yalnizca yazilan metinle eslesen firmalar kalir. Listeden secim
    yapilabilir.
