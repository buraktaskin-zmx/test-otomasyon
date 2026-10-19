# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-004 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Hizli tarih secimi tarih alanlarini doldurur

    Detayli Arama alanini genislet ve mevcut tarih degerlerini not et. Hizli Tarih
    Secim alanindan bir secenek sec.

    Beklenen: Baslangic Tarihi ve Bitis Tarihi alanlari secilen araliga gore otomatik
    guncellenir.
