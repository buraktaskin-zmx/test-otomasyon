Feature: Banko Alacak İskonto Oluşturma
  Bekleyen bir alacak seçilip tüm tutar üzerinden iskonto oluşturma.

  Scenario: Bekleyen alacağa tüm tutar iskontosu uygulanır
    Given login with cif "DI00002018" and userid "1335"

    And navigate to "NAKİT YÖNETİMİ" "Banko İşlemleri"
    And click "Alacak İşlemleri"
    And click "Bekleyen Alacaklar"

    # Satır, vade tarihiyle seçiliyor: listedeki bütün satırlarda "TEST UNVANI"
    # yazdığı için ünvan ayırt etmiyor. Tarih veriye bağlı — liste değişirse
    # bu satır da değişmeli (ya da `select row at index 1` kullanılmalı).
    And select row "25/12/2026"
    And click "İskonto Oluştur"

    # "Tüm Tutar" ekran açıldığında zaten seçili geliyor, o yüzden ayrıca
    # tıklanmıyor. "Kısmi Tutar" senaryosu için ayrı bir adım gerekir — bkz. not.
    And click "Devam"

    # Onay ekranında yalnızca veriye bağlı olmayan iki değer doğrulanıyor.
    # Faiz oranı, faiz tutarı, net tutar ve iskonto tarihi her gün değişir;
    # onları doğrulamak yarın kendiliğinden kırılan bir test yapar.
    Then verify the "Ödeme Tutarı" has amount "1.000,00"
    And verify the "Vade Tarihi" field equals "25/12/2026"

    And click "Onay"

    # Sonuç ekranının metni henüz görülmedi. Bir kez görüp buraya
    # `verify "<metin>" text is displayed` yazılacak; o adım hem doğrular hem
    # 15 saniyeye kadar kendiliğinden bekler.
    And wait "15" seconds
