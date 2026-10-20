# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-002 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Kayit Sayisi ile Toplam Tutar tutarlidir

    Sorgu calistir ve sonuclari incele.

    Beklenen: Kayit Sayisi 0 olan her satirin Toplam Tutar degeri 0,00 olmalidir.
