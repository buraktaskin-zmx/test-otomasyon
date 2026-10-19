# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-004 @R-AL-009 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Gelecek tarih araligi bos sonuc dondurur

    Firma ve doviz cinsini sec. Baslangic ve Bitis Tarihi alanlarini gelecekte bir
    araliga ayarla (ornek: bugunden bir yil sonrasi). Listele dugmesine tikla.

    Beklenen: Tabloda "Kayit bulunamadi." mesaji gorunur.

    Not: Bos sonuc, verisi olmayan firma aramak yerine tarih filtresiyle uretilir.
    Boylece senaryo test ortamindaki veriden bagimsiz calisir.
