=== SCREEN CATALOG ===
app: NGC
flow: Banko Payment Request

navigation:
main_menu: "NAKİT YÖNETİMİ"
sub_menu: "Ödeme Oluştur / Talep Et"
entry_button: "Ödeme Talep Et"

fields:
- label: "TCKN / VKN"
  type: text
  verifiable: true
- label: "Ad Soyad / Unvan"
  type: text
  verifiable: true
- label: "Ödeme Tutarı"
  type: amount
  verifiable: true
- label: "Vade Tarihi"
  type: date
  verifiable: true
- label: "Açıklama"
  type: text
  verifiable: true
- label: "Alıcı Hesabı"
  type: account_picker
  accounts:
    - "0006 7010 0000 0003 4322"

buttons:
- "Devam"
- "Onay"

success_message: "Ödeme talebiniz karşı tarafa iletilmiştir. Tarafınıza yapılacak ödemenin detaylarını Banko İşlemleri'nden görüntüleyebilirsiniz."


=== LOGIN ===
app: NGC
cif: "<cif>"
userid: "<userid>"     # skip for NGI

=== TEST INTENT ===
<natural language description of the scenario + the exact data values to use>

Generate the .feature file now.