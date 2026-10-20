# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-004 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Sorgu sonuclari tarih araligi icinde kalir

    Firma ve doviz cinsini sec. Baslangic ve Bitis Tarihi alanlarini bilinen bir
    araliga ayarla. Listele dugmesine tikla.

    Beklenen: Tabloda gorunen her satirin Olusturma Tarihi, belirlenen araligin icindedir.
