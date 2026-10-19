# Roller ve Yetkiler — Maaş Ödemeleri / Alıcı Listesi

> Bu dosya v1'de yalnızca yönlendirir, denetlenmez.

## Bilinen yetki kuralları

| Kural | İçerik |
|---|---|
| R-AL-001 | Kullanıcı yalnızca yetkili olduğu firmaları ve onlara ait listeleri görür |
| R-AD-014 | Yetkisiz kullanıcı kayıt ekleyemez, güncelleyemez, silemez |

## Belirsiz noktalar

- Yetkisiz kullanıcıda butonlar **gizli mi, pasif mi, hata mı veriyor?** (S-11)
- Liste oluşturma, liste silme ve kayıt yönetimi **ayrı yetkiler mi**, tek yetki mi?
- Maaş ödemesinde yaygın olan **oluşturan / onaylayan ayrımı** bu ekranlarda
  var mı? Confluence dokümanında geçmiyor. Muhtemelen ödeme akışında
  (E3 sonrası) devreye giriyor — bu sürümde kapsam dışı.

## Test kullanıcıları

| Alias | Rol | Durum |
|---|---|---|
| `MAKER_01` | Tam yetkili — sorgulama, oluşturma, silme, kayıt yönetimi | ✓ mevcut |
| `VIEWER_01` | Yalnızca görüntüleme | ⚠ henüz temin edilmedi |
| `OTHER_FIRM_01` | Farklı firma yetkisi olan kullanıcı (R-AL-001 testi için) | ⚠ henüz temin edilmedi |

İki eksik kullanıcı temin edilene kadar yetki senaryoları yazılamaz.
Bu durum `test-data.yaml`'da `cozum: eksik` olarak kayıtlı, yani ilgili
senaryolar Ürünler ekranında "eksik" ön koşuluyla görünür.
