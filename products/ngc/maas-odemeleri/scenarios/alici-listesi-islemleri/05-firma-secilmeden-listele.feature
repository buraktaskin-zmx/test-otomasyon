# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-011 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Firma secilmeden Listele calismaz

    Ekrani ac, Detayli Arama alanini genislet. Firma alanini bos birak, Doviz Cinsi
    olarak TL sec. Listele butonuna tikla.

    Beklenen: Sorgu calismaz, tablo sonucla dolmaz.
