# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-012 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Yeni Liste Olustur secimden bagimsiz calisir

    Hicbir satir secme. Islemler panelinden Yeni Liste Olustur secenegine tikla.

    Beklenen: Yeni Liste Olustur ekrani acilir. Ekranin Bilgi Girisi adiminda oldugu gorulur.
