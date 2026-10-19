# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-002 @R-AL-003 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Firma ve doviz secilince sorgu calisir

    Firma alanindan test firmasini, Doviz Cinsi olarak TL sec. Listele butonuna
    tikla.

    Beklenen: Sorgu calisir. Tabloda ya o firmaya ait alici listeleri gorunur ya da kayit
    yoksa bilgi mesaji gorunur.
