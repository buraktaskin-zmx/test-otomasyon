# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-014 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Doviz cinsi alani searchable select olarak davranir

    Doviz Cinsi alanina tikla ve bir metin yaz.

    Beklenen: Secenekler yazilan metne gore filtrelenir.
