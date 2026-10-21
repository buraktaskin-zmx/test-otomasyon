@R-AL-014 @needs:none
Feature: Alici Listesi Islemleri

  Scenario: Firma aramasinda eslesme olmazsa secim yapilamaz

    Firma alanina tikla. Hicbir firmayla eslesmeyecek bir metin yaz (ornek:
    "zzzqqq").

    Beklenen: Sonuc listesi bos kalir, secim yapilamaz.
