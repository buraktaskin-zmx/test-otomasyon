# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-012 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Secim yapmadan Sil ile ilerlenemez

    Sorgu calistir ama hicbir satir secme. Islemler panelinden Sil secenegine tikla.

    Beklenen: Silme onay ekrani acilmaz, mevcut ekranda kalinir.
