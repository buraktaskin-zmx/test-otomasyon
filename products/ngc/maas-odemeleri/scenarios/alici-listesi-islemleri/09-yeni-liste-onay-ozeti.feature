# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.
#
# Devam'da durur, Onay'a basmaz: kayit olusturmaz.

@R-YL-002 @R-YL-006 @needs:FIRMA_TEST
Feature: Alici Listesi Islemleri

  Scenario: Yeni liste bilgileri onay ozetinde gorunur

    On kosul: ilgili firma verisi bulunabilmeli ve Doviz Cinsi TL secilebilmeli.

    Alici Listesi Islemleri ekranini ac. Islemler panelinden Yeni Liste Olustur'a
    tikla. Firma alanindan 07954-752 ile baslayan firmayi sec. Doviz Cinsi olarak
    TL sec. Alici Listesi Adi alanina dene3 yaz. Devam'a bas. Onay'a basma.

    Beklenen: Islem Bilgileri ekrani gelir ve su degerler gorunur:
    Firma Adi / Kodu: SAINT_GOBAIN Test / 07954
    Firma Subesi: ATASEHIR (00752)
    Doviz Cinsi: TL
    Alici Listesi Adi: dene3
