# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-013 @needs:IKI_LISTE
Feature: Alici Listesi Islemleri

  Scenario: Kolonlar siralanabilir

    En az iki alici listesi donen bir sorgu calistir. Toplam Tutar kolon basligina
    tikla, siralamayi gozle. Ayni basliga tekrar tikla.

    Beklenen: Ilk tiklamada artan, ikincisinde azalan siralama uygulanir. Tutarlar sayisal
    olarak siralanir, metin olarak degil.
