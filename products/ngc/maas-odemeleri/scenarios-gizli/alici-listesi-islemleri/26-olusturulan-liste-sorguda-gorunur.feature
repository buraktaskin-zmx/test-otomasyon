# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-AL-007 @R-YL-007 @needs:LISTE_BOS @mutating
Feature: Alici Listesi Islemleri

  Scenario: Olusturulan liste sorgu ekraninda gorunur

    Yeni Liste Olustur ile AUTO_<zaman damgasi> adli bir liste olustur (firma: test
    firmasi, doviz: TL). Onay adimini tamamla. Alici Listesi Islemleri ekranina don.
    Ayni firma ve dovizle, bugunu kapsayan bir tarih araliginda sorgu calistir.

    Beklenen: Yeni liste tabloda gorunur. Kayit Sayisi 0, Toplam Tutar 0,00 olmalidir. Listeye
    bir Grup No atanmistir.

    Not: Temizlik: olusturulan liste 27 ile silinir.
