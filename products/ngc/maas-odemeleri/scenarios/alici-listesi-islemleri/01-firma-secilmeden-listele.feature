# Kütüğe kayıtlı bir senaryonun biçimi.
#
# Etiketler bağları kurar ve başka hiçbir yerde tekrarlanmaz:
#   @R-AL-011        -> rules.yaml'daki kural
#   @needs:FIRMA_LISTELI -> test-data.yaml'daki alias (ön koşul)
# Cucumber tanımadığı etiketi yok sayar, ScenarioValidator yalnızca adımlara
# bakar; yani etiketli bir dosya etiketsizi gibi doğrulanır ve koşar.
#
# UYARI: alici-listesi-islemleri ekranının kataloğu henüz üretilmedi ve alias
# çözücü (MAKER_01 -> gerçek cif) v2 işi. Bu dosya bugün BİÇİM örneğidir,
# koşulabilir bir test değildir.

@R-AL-011 @needs:MAKER_01 @needs:FIRMA_LISTELI
Feature: Alıcı Listesi İşlemleri

  Scenario: Firma seçilmeden Listele çalışmaz
    Firma alanı boş bırakılıp Listele tıklanır; sorgu çalışmaz ve
    zorunlu alan uyarısı görünür.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    And navigate to "Ödemeler" "Maaş Ödemeleri"
    And click "Alıcı Listesi İşlemleri"
    And select "TL" from "Döviz Cinsi"
    And click "Listele"
    Then verify "Seçim yapınız." text is displayed
