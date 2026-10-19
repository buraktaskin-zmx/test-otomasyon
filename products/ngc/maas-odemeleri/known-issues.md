# Bilinen Hatalar — Maaş Ödemeleri / Alıcı Listesi

> **Agent için:** Bu dosyadaki davranışlara senaryo ÜRETME.
> Burada yazanlar "sistemin şu anki yanlış davranışı"dır, beklenti değildir.
> İlgili alanı test eden bir senaryo yazarken bu durumlara girmekten kaçın.

---

## B-01 — Aynı isimle liste oluşturma UI'da ele alınmamış

| | |
|---|---|
| **Ekran** | Yeni Liste Oluştur (E5) |
| **Mevcut davranış** | Uzman servis hata dönüyor, UI bu hatayı yakalamıyor; kullanıcıya anlamlı bir mesaj gösterilmiyor |
| **Olması gereken** | "Bu isimde bir alıcı listesi zaten var" benzeri, anlaşılır bir hata mesajı |
| **Etki** | Orta — kullanıcı ne olduğunu anlamıyor |
| **Durum** | Jira'ya açılacak |
| **Düzeltilince** | R-YL-008 kuralı yazılacak, mesaj metni `rules.yaml`'a eklenecek, senaryo üretilecek |

---

## B-02 — Kayıt silme popup'ında liste silme mesajı gösteriliyor

| | |
|---|---|
| **Ekran** | Alıcı Listesi Detay (E2) — kayıt silme onay popup'ı |
| **Mevcut davranış** | Bir *kayıt* silindiğinde "Alıcı Listesi silme işleminiz başarıyla gerçekleşmiştir." mesajı çıkıyor |
| **Olması gereken** | Kayıt silmeye ait bir mesaj, ör. "Kayıt silme işleminiz başarıyla gerçekleşmiştir." |
| **Etki** | Düşük — kozmetik, ancak kullanıcıda "listeyi mi sildim?" tereddüdü yaratabilir |
| **Not** | Muhtemelen yanlış dil kaynağı anahtarı kullanılmış |
| **Durum** | Jira'ya açılacak |

### Mesaj metni karşılaştırması

Bu iki mesaj birbirine çok benziyor ancak **birebir aynı değil**. Validator
metinleri harfi harfine karşılaştırdığı için ikisi de ayrı ayrı kayıtlıdır:

```
Liste silme (E4, doğru):
  "Alıcı Listesi Silme işleminiz başarıyla gerçekleştirilmiştir."
                    ^^^^^                        ^^^^^^^^^^^^^^^

Kayıt silme (E2, hatalı metin):
  "Alıcı Listesi silme işleminiz başarıyla gerçekleşmiştir."
                    ^^^^^                        ^^^^^^^^^^
```

**Ölçülmüş tuzak:** iki metin arasındaki düzenleme mesafesi 5. Kayıt silmeyi
test eden bir senaryo ekrandaki gerçek metni doğrularsa, bu metin hiçbir
kuralda tanımlı olmadığı için validator reddeder — ve en yakın bilinen mesaj
olarak **liste silme** metnini önerir. Öneriye tıklayan analist, doğrulamadan
geçen ama gerçek ekranda patlayan bir senaryo elde eder.

B-02 düzeltildiğinde ikinci metin değişecek; `rules.yaml` güncellenmeli.
