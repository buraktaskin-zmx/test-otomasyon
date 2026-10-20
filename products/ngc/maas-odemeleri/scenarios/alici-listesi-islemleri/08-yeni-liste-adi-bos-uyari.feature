# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-YL-003 @needs:FIRMA_TEST
Feature: Alici Listesi Islemleri

  Scenario: Yeni liste adi bos birakilinca uyari cikar

    On kosul: ilgili firma verisi bulunabilmeli ve Doviz Cinsi TL secilebilmeli.

    Alici Listesi Islemleri ekranini ac. Islemler panelinden Yeni Liste Olustur'a
    tikla. Firma alanindan 07954-752 ile baslayan firmayi sec. Doviz Cinsi olarak
    TL sec. Alici Listesi Adi alanini bos birak. Devam'a bas.

    Beklenen: Alici Listesi Adi alani icin "Giriş yapınız." uyarisi gorunur,
    ekran Onay adimina gecmez.
