# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-LS-001 @R-LS-002 @needs:LISTE_BOS
Feature: Alici Listesi Islemleri

  Scenario: Secili listeyle silme onay ekrani acilir

    Sorgu calistir, AUTO_ onekli bir test listesi sec. Secilen satirin Alici Listesi
    Adi, Toplam Tutar ve Kayit Sayisi degerlerini not et. Sil secenegine tikla.

    Beklenen: Alici Listesi Sil onay ekrani acilir. Ekranda Firma Adi/Kodu, Firma Subesi,
    Alici Listesi Adi, Toplam Tutar ve Toplam Kayit Sayisi gorunur; degerler not
    edilenlerle aynidir.

    Not: Bu senaryoda Onay dugmesine BASILMAZ. Silme isleminin tamami 27'de test edilir.
