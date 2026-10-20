# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-005 @needs:LISTE_BOS
Feature: Alici Listesi Islemleri

  Scenario: Detay ekranindan Geri ile donulur

    Bir listenin Detay ekranini ac. Geri butonuna tikla.

    Beklenen: Alici Listesi Islemleri ekranina donulur.
