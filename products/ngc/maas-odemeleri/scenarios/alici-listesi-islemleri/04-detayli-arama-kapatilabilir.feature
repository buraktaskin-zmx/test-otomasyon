# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-015 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Detayli Arama kapatilabilir

    Detayli Arama baglantisina tikla, alanlarin acildigini gor. Ayni baglantiya
    tekrar tikla.

    Beklenen: Tarih alanlari gizlenir, filtre alani ilk haline doner.
