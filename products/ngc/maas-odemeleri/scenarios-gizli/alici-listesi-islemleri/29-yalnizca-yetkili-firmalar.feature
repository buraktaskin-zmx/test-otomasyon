# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-001 @needs:OTHER_FIRM_01
Feature: Alici Listesi Islemleri

  Scenario: Yalnizca yetkili firmalar listelenir

    Firma alanini ac ve secenekleri incele.

    Beklenen: Yalnizca kullanicinin yetkili oldugu firmalar listelenir.

    Not: Ikinci bir test kullanicisi temin edilene kadar bu senaryo yalnizca KISMEN
    dogrulanabilir: "baska firmanin listesi donmuyor" kontrolu yapilamaz.
