# Senaryo DOGAL DILLE yazilir; ajan tarayiciyi surerek uygular.
# Gherkin adimlari ayni senaryonun DETERMINISTIK TEKRARIDIR ve sonradan
# yazilir -- adimi olmayan bir dosya "0 adim" gorunur, ve bu durustur:
# "AI ile calistir" bugun kosar, "Kayitli adimlarla calistir" henuz kosmaz.

@R-LS-003 @R-LS-004 @R-AL-006 @needs:LISTE_BOS @mutating
Feature: Alici Listesi Islemleri

  Scenario: Liste silinir ve sorgudan duser

    AUTO_ onekli, 0 kayitli bir test listesi olustur veya mevcut birini sec. Sil
    secenegine tikla, onay ekranindaki bilgileri dogrula, Onay dugmesine bas. Ayni
    sorguyu tekrar calistir.

    Beklenen: "Alici Listesi Silme isleminiz basariyla gerceklestirilmistir." mesaji gorunur.
    Silinen liste sorgu sonucunda artik yer almaz.
