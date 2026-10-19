# Banko — cevaplanmamış sorular

Bu dosya **modele gönderilmez.** Var olma sebebi tam tersi: burada yazan bir
şey hakkında varsayım üretilmesini engellemek. Üretim panelinde uyarı olarak
görünür.

## Ödeme Oluştur / Talep Et ekranı

- **"Alıcı Hesabı" hangi Select2 sürümü?** Ekran görüntüsündeki bakiyeli açılır
  liste (Güncel Bakiye / Esnek Hesap Limiti / Kullanılabilir Bakiye)
  kataloğumuzun **sürülemeyen v3** varyantına benziyor; `select account` adımı
  yalnızca v4'ü sürebiliyor. v3 çıkarsa bu ekrandaki senaryoların tamamı ilk
  alanda durur. Cevabı "Ekranı Keşfet" veriyor: v3'ü `UNSUPPORTED_V3` diye
  işaretliyor. **Başka hiçbir şeyden önce bu.**

- **Menü adı "NAKİT YÖNETİMİ" mi "Nakit Akışım" mı?** Katalog birincisini
  yazıyor, 16/10/2026 ekran görüntüsündeki menü başlığı ikincisi. Yanlış olan
  30 saniyelik sessiz bir timeout olarak çıkar.

- **"Ödeme Talebi Türü" seçenekleri neler?** Katalogda bu alan hiç yok.
  Görünen tek değer "Vadesinde Peşin Ödensin". Tür seçimi **Vade Tarihi'ni
  zorunlu/opsiyonel yapıyor mu?** Yapıyorsa her tür ayrı bir case.

- **Üç onay kutusu hangi alanları açıyor?**
  - "Garanti tutarı girmek istiyorum."
  - "Fatura numarası eklemek istiyorum."
  - "İşlemi Akıllı Rehbere Ekle"
  Açılan alanların etiketleri ve zorunlulukları bilinmiyor.

- **Garanti tutarı ödeme tutarından büyük olabilir mi?** Reddediliyorsa mesajı
  nedir?

- **TCKN/VKN alanı yalnızca rakam mı kabul ediyor, uzunluk kontrolü var mı?**
  Etiketten tahmin etmeyin — bu uygulamada "Fatura No" `FTR-2026-0001` kabul
  ediyor. DOM taraması `numeric` ve `max_length`'i ölçsün.

- **"Akıllı Rehber'den kayıt seçimi" butonu neyi açıyor?** Bir modal/kayıt
  seçici; **bunu süren bir adım yok.** Kapsama katacağı tek şey otomatik
  doldurmanın doğruluğu, o yüzden sonraya bırakıldı.

- **Dosya Yükleme dalı** kendi kataloğunu istiyor. `upload` adımı var, yani dal
  sürülebilir — ama ekran hiç gezilmedi.
