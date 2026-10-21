@R-AL-004 @R-AL-009 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Gelecek tarih araligi bos sonuc dondurur

    Firma ve doviz cinsini sec. Baslangic ve Bitis Tarihi alanlarini gelecekte bir
    araliga ayarla (ornek: bugunden bir yil sonrasi). Listele dugmesine tikla.

    Beklenen: Tabloda "Kayit bulunamadi." mesaji gorunur.

    Not: Bos sonuc, verisi olmayan firma aramak yerine tarih filtresiyle uretilir.
    Boylece senaryo test ortamindaki veriden bagimsiz calisir.
