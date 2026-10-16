# Test dosyaları

`upload {string} {string}` adımının ikinci argümanı bir **dosya adıdır, yol
değildir**, ve burada aranır.

```gherkin
And upload "Dosya" "fatura-listesi.xlsx"
```

→ `testdata/fatura-listesi.xlsx`

Başka bir dizin kullanmak için `UPTEST_TESTDATA_DIR` ortam değişkenini ayarlayın.
Senaryoda tam yol da yazılabilir, ama yazmayın: senaryoyu bir analist okuyup
düzenliyor ve makineye özgü bir yol hem okunmaz hem bir sonraki makinede yanlış
olur. Sözlüğün geri kalanı da aynı sebeple seçici değil etiket alıyor.

## İçeriği neden commit edilmiyor

`.gitignore` bu dizinin **içeriğini** dışarıda tutuyor, yalnızca bu README
izleniyor. Sebebi `DO_NOT_COMMIT/` ile aynı: buraya konacak dosyalar gerçek
bir bankacılık akışına yüklenmek üzere hazırlanmış olur ve içlerinde müşteri
numarası, hesap numarası, tutar bulunabilir. Bir fatura yükleme şablonu,
tanımı gereği, test verisi taşıyan bir dosyadır.

Dosyaları VDI'da bu dizine elle koyun. Hangi dosyanın hangi akış için
gerektiğini senaryonun kendisi zaten söylüyor.

## Örnek dosya neden yok

Koymadık, çünkü uygulamanın beklediği şablonu bilmiyoruz. Yanlış biçimli bir
örnek dosya, "adım çalışmıyor" gibi görünen ama aslında "dosya biçimi yanlış"
olan bir hataya yol açardı — ve bu ikisi birbirinden ayırt edilmesi en zor
hata çiftlerinden biri.
