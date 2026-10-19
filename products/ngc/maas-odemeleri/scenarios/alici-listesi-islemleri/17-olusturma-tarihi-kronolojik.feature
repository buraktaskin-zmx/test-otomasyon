# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-013 @needs:IKI_LISTE
Feature: Alici Listesi Islemleri

  Scenario: Olusturma Tarihi kolonu kronolojik siralanir

    Sorgu calistir. Olusturma Tarihi kolon basligina tikla.

    Beklenen: Satirlar tarih sirasina gore dizilir.
