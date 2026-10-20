# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-004 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Takvimde Bugun butonu

    Baslangic Tarihi takvimini ac. Bugun butonuna tikla.

    Beklenen: Alan bugunun tarihiyle dolar.
