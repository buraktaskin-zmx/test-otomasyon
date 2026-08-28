Feature: Transfer to another account
  User has account, auth and money.


  Scenario: User can transfer money to another account.
    Given login with cif "JA38121749" and userid "3014"
    And navigate to "PARA TRANSFERLERİ" "Hesaplarınız Arası Havale"
    And wait "5" seconds
    And select account "0006 7010 0000 0100 0001" from "Ödeme Aracı"

    And select account "0006 7010 0000 0200 0124" from "Alıcı Hesabınız"

    And enter amount "Tutar" "1.000,00"

    And enter "Açıklama" "TEST OTOMASYON"

    And click "Devam"
    And click "Onay"
    Then verify "Kullanıcı limit aşımı." text is displayed