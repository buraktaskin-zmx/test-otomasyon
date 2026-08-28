package com.ykb.uplift.uptest.playwright.components.selectbox.account;

import java.math.BigDecimal;

public class Money {

    private final BigDecimal amount;

    private final String currency;

    private static BigDecimal parseTrDecimal(String s) {
        if (s == null) return null;
        String normalized = s.trim()
                .replace(".", "")   // binlik ayırıcıyı kaldır
                .replace(",", "."); // ondalık ayırıcıyı noktaya çevir
        return new BigDecimal(normalized);
    }

    public Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public Money(String value){
        this.amount = parseTrDecimal(value.split(" ")[0]);
        this.currency = value.split(" ")[1];
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }
}
