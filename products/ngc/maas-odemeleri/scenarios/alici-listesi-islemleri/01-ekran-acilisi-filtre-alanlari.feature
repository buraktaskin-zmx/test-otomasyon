# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-014 @R-AL-015 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Ekran acildiginda filtre alanlari gorunur

    Alici Listesi Islemleri ekranini ac. Ekranda Firma, Doviz Cinsi ve Hizli Tarih
    Secim alanlarinin bulundugunu dogrula. Sag tarafta Detayli Arama baglantisinin
    bulundugunu dogrula.

    Beklenen: Uc filtre alani da gorunur durumdadir, hicbiri secili degildir.
