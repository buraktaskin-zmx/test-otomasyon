# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-015 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Detayli Arama tarih alanlarini acar

    Ekrani ac. Detayli Arama baglantisina tikla.

    Beklenen: Baslangic Tarihi ve Bitis Tarihi alanlari ile Listele butonu gorunur hale gelir.
    Her iki tarih alaninin yaninda takvim simgesi bulunur.
