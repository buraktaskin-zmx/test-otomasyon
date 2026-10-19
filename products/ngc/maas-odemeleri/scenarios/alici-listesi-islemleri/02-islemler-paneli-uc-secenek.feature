# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-010 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Islemler panelinde uc secenek bulunur

    Ekrani ac ve hicbir satir secme. Islemler panelini incele.

    Beklenen: Detay, Sil ve Yeni Liste Olustur secenekleri gorunur durumdadir.
