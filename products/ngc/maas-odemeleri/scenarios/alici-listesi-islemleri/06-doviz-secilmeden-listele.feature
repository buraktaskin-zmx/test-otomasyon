# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-011 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Doviz cinsi secilmeden Listele calismaz

    Firma alanindan test firmasini sec, Doviz Cinsi alanini bos birak. Listele
    butonuna tikla.

    Beklenen: Sorgu calismaz, tablo sonucla dolmaz.
